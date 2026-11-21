package bms.player.beatoraja.ir;

/**
 * 単純な{@link IRResponse}の値実装。
 *
 * <p>IRプラグインは別クラスローダで動作するため、この型は公開APIとして扱う。</p>
 *
 * @param <T> レスポンスデータの型
 */
public final class SimpleIRResponse<T> implements IRResponse<T> {

	private final boolean succeeded;
	private final String message;
	private final T data;

	public SimpleIRResponse(boolean succeeded, String message, T data) {
		this.succeeded = succeeded;
		this.message = message != null ? message : "";
		this.data = data;
	}

	@Override
	public boolean isSucceeded() {
		return succeeded;
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public T getData() {
		return data;
	}
}
