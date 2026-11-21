package bms.player.beatoraja.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

/**
 * Ogg Vorbis decoder built on the bundled pure-Java jorbis library.
 *
 * <p>This class is an adaptation of libGDX's
 * {@code com.badlogic.gdx.backends.lwjgl3.audio.OggInputStream}. That class depends on backend
 * buffer utilities that are not available to shared game code. Only the buffer allocation and the
 * {@code GdxRuntimeException} wrapping were replaced; the decode loop follows the libGDX class so
 * that existing OGG assets decode identically.</p>
 *
 * <p>Output is interleaved signed 16-bit little-endian PCM in the stream's own channel count and
 * sample rate, which is what {@link PCM} consumes.</p>
 *
 * <pre>
 * Original libGDX/Slick2D license header, retained as required by its BSD license:
 *
 * Copyright (c) 2007, Slick 2D
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the distribution. Neither the name of the Slick 2D nor the names of
 * its contributors may be used to endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * </pre>
 */
public class OggVorbisDecoder extends InputStream {

	/** Granularity of the pages submitted to libvorbis, as in the libGDX class. */
	private static final int BUFFER_SIZE = 512;

	/** The conversion buffer size. */
	private int convsize = BUFFER_SIZE * 4;
	/** The buffer used to hold one decoded block of interleaved 16-bit PCM. */
	private final byte[] convbuffer;

	/** The stream we're reading the OGG file from. */
	private final InputStream input;

	/** The audio information from the OGG header. */
	private final Info oggInfo = new Info();
	/** True if we're at the end of the available data. */
	private boolean endOfStream;

	/** Sync and verify incoming physical bitstream. */
	private final SyncState syncState = new SyncState();
	/** Take physical pages, weld into a logical stream of packets. */
	private final StreamState streamState = new StreamState();
	/** One Ogg bitstream page. Vorbis packets are inside. */
	private final Page page = new Page();
	/** One raw packet of data for decode. */
	private final Packet packet = new Packet();
	/** Struct that stores all the bitstream user comments. */
	private final Comment comment = new Comment();
	/** Central working state for the packet-&gt;PCM decoder. */
	private final DspState dspState = new DspState();
	/** Local working space for packet-&gt;PCM decode. */
	private final Block vorbisBlock = new Block(dspState);

	/** Scratch buffer handed to libvorbis; owned by {@link #syncState}. */
	private byte[] buffer;
	/** Number of bytes read by the last read. */
	private int bytes;
	/** True if we've reached the end of the current bit stream. */
	private boolean endOfBitStream = true;
	/** True if we've initialised the OGG info block. */
	private boolean inited = false;

	/** The index into the decoded PCM byte array we currently read from. */
	private int readIndex;
	/** Growable buffer holding decoded interleaved PCM until {@link #read(byte[])} drains it. */
	private byte[] pcmBuffer = new byte[65536];
	/** Number of valid bytes in {@link #pcmBuffer}. */
	private int pcmPosition;
	/** Total number of compressed bytes reported by {@link InputStream#available()} at open time. */
	private int total;
	/** Channel count of the decoded stream, captured at the Vorbis headers (jorbis clears Info at EOF). */
	private int decodedChannels;
	/** Sample rate of the decoded stream, captured at the Vorbis headers. */
	private int decodedSampleRate;

	/**
	 * Creates a decoder for the given OGG stream. The constructor also parses the three Vorbis headers, so
	 * {@link #getChannels()} / {@link #getSampleRate()} are valid immediately afterwards.
	 *
	 * @param input stream positioned at the beginning of an Ogg Vorbis bitstream
	 */
	public OggVorbisDecoder(InputStream input) throws IOException {
		this.input = input;
		try {
			total = Math.max(0, input.available());
		} catch (IOException ignored) {
			total = 0;
		}
		convbuffer = new byte[convsize];
		init();
	}

	/** Number of compressed bytes available on the underlying stream at open time (capacity hint only). */
	public int getLength() {
		return total;
	}

	public int getChannels() {
		return decodedChannels;
	}

	public int getSampleRate() {
		return decodedSampleRate;
	}

	/** Initialise the streams involved in the streaming of OGG data. */
	private void init() throws IOException {
		syncState.init();
		readPCM();
	}

	@Override
	public int available() {
		return endOfStream ? 0 : 1;
	}

	/**
	 * Grabs the first page and the two remaining Vorbis headers.
	 *
	 * @return true when a page was available
	 */
	private boolean getPageAndPacket() throws IOException {
		// Submit a small block to libvorbis' Ogg layer and grab the first page.
		int index = syncState.buffer(BUFFER_SIZE);
		if (index == -1) return false;

		buffer = syncState.data;
		if (buffer == null) {
			endOfStream = true;
			return false;
		}

		bytes = input.read(buffer, index, BUFFER_SIZE);
		syncState.wrote(Math.max(0, bytes));

		// Get the first page.
		if (syncState.pageout(page) != 1) {
			// Have we simply run out of data? If so, we're done.
			if (bytes < BUFFER_SIZE) return false;

			// Error case. Must not be Vorbis data.
			throw new IOException("Input does not appear to be an Ogg bitstream.");
		}

		// Get the serial number and set up the rest of decode.
		streamState.init(page.serialno());

		// Extract the initial header from the first page and verify that the stream is Vorbis data.
		oggInfo.init();
		comment.init();
		if (streamState.pagein(page) < 0) {
			throw new IOException("Error reading first page of Ogg bitstream.");
		}
		if (streamState.packetout(packet) != 1) {
			throw new IOException("Error reading initial header packet.");
		}
		if (oggInfo.synthesis_headerin(comment, packet) < 0) {
			throw new IOException("Ogg bitstream does not contain Vorbis audio data.");
		}

		// The next two packets are the comment and codebook headers. They may span multiple pages.
		int i = 0;
		while (i < 2) {
			while (i < 2) {
				int result = syncState.pageout(page);
				if (result == 0) break; // Need more data

				// Don't complain about missing or corrupt data yet; packetout will catch it.
				if (result == 1) {
					streamState.pagein(page);
					while (i < 2) {
						result = streamState.packetout(packet);
						if (result == 0) break; // Need more data
						if (result == -1) {
							// Corrupt or missing data in a header is fatal.
							throw new IOException("Corrupt secondary header.");
						}
						oggInfo.synthesis_headerin(comment, packet);
						i++;
					}
				}
			}
			// No harm in not checking before adding more.
			index = syncState.buffer(BUFFER_SIZE);
			if (index == -1) return false;
			buffer = syncState.data;
			bytes = input.read(buffer, index, BUFFER_SIZE);
			if (bytes <= 0 && i < 2) {
				// A negative read also means end of stream.
				throw new IOException("End of file before finding all Vorbis headers.");
			}
			syncState.wrote(Math.max(0, bytes));
		}

		convsize = BUFFER_SIZE / oggInfo.channels;
		// Remember the stream format: jorbis Info.clear() runs at EOF and callers query the format afterwards.
		decodedChannels = oggInfo.channels;
		decodedSampleRate = oggInfo.rate;

		// We got and parsed all three headers; initialize the Vorbis packet->PCM decoder.
		dspState.synthesis_init(oggInfo);
		vorbisBlock.init(dspState);
		return true;
	}

	/** Decodes PCM from the OGG stream, following the jogg/jorbis example code. */
	private void readPCM() throws IOException {
		boolean wrote = false;

		while (true) { // We repeat if the bitstream is chained.
			if (endOfBitStream) {
				if (!getPageAndPacket()) {
					break;
				}
				endOfBitStream = false;
			}

			if (!inited) {
				inited = true;
				return;
			}

			float[][][] pcm = new float[1][][];
			int[] index = new int[oggInfo.channels];
			// The rest is a straight decode loop until end of stream.
			while (!endOfBitStream) {
				while (!endOfBitStream) {
					int result = syncState.pageout(page);

					if (result == 0) {
						break; // Need more data
					} else if (result == -1) {
						Logger.getGlobal().warning("Error reading OGG: corrupt or missing data in bitstream.");
					} else {
						streamState.pagein(page); // Can safely ignore errors at this point.
						while (true) {
							result = streamState.packetout(packet);

							if (result == 0) break; // Need more data
							if (result != -1) {
								// We have a packet; decode it.
								int samples;
								if (vorbisBlock.synthesis(packet) == 0) { // Test for success!
									dspState.synthesis_blockin(vorbisBlock);
								}

								// pcm is a multichannel float vector: pcm[0] is left, pcm[1] is right.
								// Convert floats (-1..1) to interleaved signed 16-bit PCM.
								while ((samples = dspState.synthesis_pcmout(pcm, index)) > 0) {
									float[][] channelSamples = pcm[0];
									int bout = samples < convsize ? samples : convsize;

									for (int channel = 0; channel < oggInfo.channels; channel++) {
										int ptr = channel * 2;
										int mono = index[channel];
										for (int j = 0; j < bout; j++) {
											int val = (int) (channelSamples[channel][mono + j] * 32767.);
											// Guard against clipping.
											if (val > 32767) {
												val = 32767;
											}
											if (val < -32768) {
												val = -32768;
											}
											// Always little-endian: the PCM classes read LE shorts.
											convbuffer[ptr] = (byte) (val & 0xff);
											convbuffer[ptr + 1] = (byte) ((val >>> 8) & 0xff);
											ptr += 2 * oggInfo.channels;
										}
									}

									int bytesToWrite = 2 * oggInfo.channels * bout;
									ensurePcmCapacity(bytesToWrite);
									System.arraycopy(convbuffer, 0, pcmBuffer, pcmPosition, bytesToWrite);
									pcmPosition += bytesToWrite;

									wrote = true;
									dspState.synthesis_read(bout); // Tell libvorbis how many samples we consumed.
								}
							}
						}
						if (page.eos() != 0) {
							endOfBitStream = true;
						}

						if (!endOfBitStream && wrote) {
							return;
						}
					}
				}

				if (!endOfBitStream) {
					bytes = 0;
					int bufferIndex = syncState.buffer(BUFFER_SIZE);
					if (bufferIndex >= 0) {
						buffer = syncState.data;
						bytes = input.read(buffer, bufferIndex, BUFFER_SIZE);
					}
					syncState.wrote(Math.max(0, bytes));
					if (bytes <= 0) {
						endOfBitStream = true;
					}
				}
			}

			// Clean up this logical bitstream; before exit we see if it is followed by another chained one.
			streamState.clear();
			// ogg_page and ogg_packet structs always point to storage in libvorbis; never free them directly.
			vorbisBlock.clear();
			dspState.clear();
			oggInfo.clear(); // Must be called last.
		}

		syncState.clear();
		endOfStream = true;
	}

	private void ensurePcmCapacity(int extra) {
		int required = pcmPosition + extra;
		if (required <= pcmBuffer.length) {
			return;
		}
		int capacity = pcmBuffer.length;
		while (capacity < required) {
			capacity = capacity < 1 << 20 ? capacity * 2 : capacity + (1 << 20);
		}
		byte[] grown = new byte[capacity];
		System.arraycopy(pcmBuffer, 0, grown, 0, pcmPosition);
		pcmBuffer = grown;
	}

	@Override
	public int read() throws IOException {
		if (readIndex >= pcmPosition) {
			pcmPosition = 0;
			readIndex = 0;
			readPCM();
		}
		if (readIndex >= pcmPosition) {
			return -1;
		}
		return pcmBuffer[readIndex++] & 0xff;
	}

	/** True when the whole stream has been decoded and drained. */
	public boolean atEnd() {
		return endOfStream && readIndex >= pcmPosition;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (b == null) throw new NullPointerException("b");
		if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
		if (len == 0) return 0;
		for (int i = 0; i < len; i++) {
			int value = read();
			if (value >= 0) {
				b[off + i] = (byte) value;
			} else {
				return i == 0 ? -1 : i;
			}
		}
		return len;
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public void close() throws IOException {
		// The decode loop already clears the jorbis structures when it reaches the end of the
		// bitstream; calling their clear() methods twice throws (Info.clear() nulls its arrays).
		endOfStream = true;
		input.close();
	}
}
