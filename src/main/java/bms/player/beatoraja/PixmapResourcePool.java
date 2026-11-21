package bms.player.beatoraja;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import bms.player.beatoraja.AppPaths;
import bms.player.beatoraja.song.SongResource;
import bms.player.beatoraja.song.SongResources;

/**
 * Pixmapリソースプール
 *
 * <p>Encoded image bytes are decoded directly with libGDX's built-in
 * {@link Pixmap#Pixmap(byte[], int, int)} decoder. CIM files are decoded by libGDX's pure-Java
 * {@link PixmapIO#readCIM} reader.</p>
 *
 * @author exch
 */
public class PixmapResourcePool extends ResourcePool<String, PixmapResourcePool.PixmapResource> {

	/** Point-in-time estimate for native Pixmap memory retained by this pool. */
	public record MemoryStatistics(int pixmapCount, long estimatedNativeBytes, int songResourceCount) {
	}

	public static final class PixmapResource {
		private final Pixmap pixmap;
		private final int frameCount;
		private final int frameWidth;
		private final int frameHeight;
		private final int columns;
		private final int rows;

		public PixmapResource(Pixmap pixmap) {
			this(pixmap, 1, pixmap.getWidth(), pixmap.getHeight(), 1, 1);
		}

		public PixmapResource(Pixmap pixmap, int frameCount, int frameWidth, int frameHeight, int columns, int rows) {
			this.pixmap = pixmap;
			this.frameCount = frameCount;
			this.frameWidth = frameWidth;
			this.frameHeight = frameHeight;
			this.columns = columns;
			this.rows = rows;
		}

		public Pixmap getPixmap() {
			return pixmap;
		}

		public int getFrameCount() {
			return frameCount;
		}

		public int getFrameWidth() {
			return frameWidth;
		}

		public int getFrameHeight() {
			return frameHeight;
		}

		public int getColumns() {
			return columns;
		}

		public int getRows() {
			return rows;
		}

		public void dispose() {
			pixmap.dispose();
		}
	}

	public PixmapResourcePool() {
		super(1);
	}

	public PixmapResourcePool(int maxgen) {
		super(maxgen);
	}

	@Override
	protected PixmapResource load(String path) {
		final PixmapResource resource = loadPictureResource(path);
		return resource != null ? convert(resource) : null;
	}

	public Pixmap getPixmap(String path) {
		PixmapResource resource = get(path);
		return resource != null ? resource.getPixmap() : null;
	}

	public PixmapResource getPixmapResource(String path) {
		return get(path);
	}

	public Pixmap getPixmap(SongResource resource) {
		PixmapResource picture = getPixmapResource(resource);
		return picture != null ? picture.getPixmap() : null;
	}

	public PixmapResource getPixmapResource(SongResource resource) {
		return get(resource.cacheKey(), () -> {
			PixmapResource picture = loadPictureResource(resource);
			return picture != null ? convert(picture) : null;
		});
	}

	public MemoryStatistics getMemoryStatistics() {
		Statistics statistics = getStatistics(resource -> estimatePixmapBytes(resource.getPixmap()));
		return new MemoryStatistics(statistics.resourceCount(), statistics.estimatedBytes(), 0);
	}

	private static long estimatePixmapBytes(Pixmap pixmap) {
		// Pixmap formats used by skin/BGA resources are at most RGBA8888. Keep this
		// deliberately conservative because libGDX does not expose native allocation size.
		return (long) pixmap.getWidth() * pixmap.getHeight() * Integer.BYTES;
	}

	/**
	 * Pixmapをload時に変換する。
	 *
	 * @param pixmap
	 * @return
	 */
	protected Pixmap convert(Pixmap pixmap) {
		return pixmap;
	}

	protected PixmapResource convert(PixmapResource resource) {
		Pixmap pixmap = convert(resource.getPixmap());
		if (pixmap == resource.getPixmap()) {
			return resource;
		}
		return new PixmapResource(pixmap);
	}

	@Override
	protected void dispose(PixmapResource resource) {
		resource.dispose();
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージ。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static Pixmap loadPicture(String path) {
		PixmapResource resource = loadPictureResource(path);
		return resource != null ? resource.getPixmap() : null;
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージリソース。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static PixmapResource loadPictureResource(String path) {
		try {
			return loadPictureResource(SongResources.fromPath(AppPaths.path(path)));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Loads an image directly from a local, archive, or remote song resource. */
	public static PixmapResource loadPictureResource(SongResource resource) {
		if (resource == null) {
			return null;
		}
		try {
			if (resource.localPath().isPresent()) {
				return loadLocalPictureResource(resource.localPath().get());
			}
			if (!resource.exists() || resource.isDirectory()) {
				return null;
			}
			String lower = resource.name().toLowerCase(Locale.ROOT);
			if (lower.endsWith(".cim") || lower.endsWith(".tga")) {
				// libGDX decodes both from a file handle; materialize archive/remote entries first.
				return loadLocalPictureResource(resource.materialize());
			}
			try (InputStream input = resource.openStream()) {
				return decodePicture(input.readAllBytes(), resource.displayPath());
			}
		} catch (Throwable e) {
			Logger.getGlobal().warning("BGAファイル読み込み失敗。" + resource.displayPath() + " : " + e.getMessage());
			return null;
		}
	}

	private static PixmapResource loadLocalPictureResource(Path path) {
		try {
			Path resolved = AppPaths.path(path);
			if (!Files.isRegularFile(resolved)) {
				return null;
			}
			String lower = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
			if (lower.endsWith(".cim")) {
				// CIM is libGDX's own compressed cache format; the bundled reader decodes it.
				try {
					return new PixmapResource(PixmapIO.readCIM(Gdx.files.absolute(resolved.toAbsolutePath().toString())));
				} catch (Throwable e) {
					Logger.getGlobal().warning("BGAファイル読み込み失敗。(CIM) " + resolved + " : " + e.getMessage());
					return null;
				}
			}
			return decodePicture(Files.readAllBytes(resolved), resolved.toString());
		} catch (Throwable e) {
			Logger.getGlobal().warning("BGAファイル読み込み失敗。" + path + " : " + e.getMessage());
			return null;
		}
	}

	/**
	 * Decodes encoded image bytes into a {@link PixmapResource}.
	 *
	 * @param bytes encoded image data (PNG/JPEG/BMP/TGA/GIF/WebP/...)
	 * @param name resource name used for log messages
	 */
	private static PixmapResource decodePicture(byte[] bytes, String name) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		try {
			return new PixmapResource(new Pixmap(bytes, 0, bytes.length));
		} catch (Throwable e) {
			Logger.getGlobal().warning("BGAファイル読み込み失敗。" + name + " : " + e.getMessage());
			return null;
		}
	}
}
