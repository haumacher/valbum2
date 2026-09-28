package de.haumacher.valbum.media_location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Answers whether the app holds {@code ACCESS_MEDIA_LOCATION} (issue #169).
 *
 * <p>
 * A {@link FlutterPlugin} and nothing more — no {@code ActivityAware} — because the question
 * needs only the application context, and the engine WorkManager starts for a background run
 * has no Activity. Registered through the generated plugin registrant, it is attached to that
 * engine exactly as to the Activity's.
 * </p>
 */
public class MediaLocationPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {

	private static final String CHANNEL = "de.haumacher.valbum/media_location";

	private static final String IS_GRANTED = "isGranted";

	private MethodChannel _channel;

	private Context _context;

	@Override
	public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
		_context = binding.getApplicationContext();
		_channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
		_channel.setMethodCallHandler(this);
	}

	@Override
	public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
		_channel.setMethodCallHandler(null);
		_channel = null;
		_context = null;
	}

	@Override
	public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (IS_GRANTED.equals(call.method)) {
			result.success(isGranted());
		} else {
			result.notImplemented();
		}
	}

	private boolean isGranted() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			// Before Android 10 there is no such permission, and nothing is redacted.
			return true;
		}
		return _context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
			== PackageManager.PERMISSION_GRANTED;
	}

}
