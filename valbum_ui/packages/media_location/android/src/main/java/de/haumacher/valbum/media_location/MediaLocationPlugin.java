package de.haumacher.valbum.media_location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Answers which media permissions the app holds: {@code ACCESS_MEDIA_LOCATION} (issue #169), and
 * whether the photos and videos may be read at all.
 *
 * <p>
 * A {@link FlutterPlugin} and nothing more — no {@code ActivityAware} — because both questions
 * need only the application context, and the engine WorkManager starts for a background run has no
 * Activity. Registered through the generated plugin registrant, it is attached to that engine
 * exactly as to the Activity's.
 * </p>
 *
 * <p>
 * The photo question exists because {@code photo_manager} cannot answer it there: its
 * {@code getPermissionState} reads the permissions through the {@code Application} of the Activity
 * it was bound to, which an engine without an Activity never sets — R8 turns the resulting null
 * check into "Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' on a
 * null object reference", and every background sync failed with it.
 * </p>
 *
 * <p>
 * Nothing here throws into the engine: a failure is answered as an error with its complete text,
 * which the app writes into its diagnostics log.
 * </p>
 */
public class MediaLocationPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {

	private static final String CHANNEL = "de.haumacher.valbum/media_location";

	private static final String IS_GRANTED = "isGranted";

	private static final String MEDIA_ACCESS = "mediaAccess";

	/** The app may read every photo and video. */
	private static final String FULL = "full";

	/** The app may read the photos and videos the user picked (Android 14 and newer). */
	private static final String LIMITED = "limited";

	/** The app may read none of them. */
	private static final String NONE = "none";

	/** {@code Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, spelled out for older SDKs. */
	private static final String READ_MEDIA_VISUAL_USER_SELECTED =
		"android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

	/** {@code Manifest.permission.READ_MEDIA_IMAGES}. */
	private static final String READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";

	/** {@code Manifest.permission.READ_MEDIA_VIDEO}. */
	private static final String READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";

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
		if (_channel != null) {
			_channel.setMethodCallHandler(null);
		}
		_channel = null;
		_context = null;
	}

	@Override
	public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		Context context = _context;
		if (context == null) {
			result.error("no_context", "The media_location plugin is not attached to an engine.", null);
			return;
		}
		try {
			if (IS_GRANTED.equals(call.method)) {
				result.success(isLocationGranted(context));
			} else if (MEDIA_ACCESS.equals(call.method)) {
				result.success(mediaAccess(context));
			} else {
				result.notImplemented();
			}
		} catch (RuntimeException ex) {
			result.error("media_location_failed", String.valueOf(ex), Log.getStackTraceString(ex));
		}
	}

	private static boolean isLocationGranted(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			// Before Android 10 there is no such permission, and nothing is redacted.
			return true;
		}
		return held(context, Manifest.permission.ACCESS_MEDIA_LOCATION);
	}

	/**
	 * Whether the photos and videos may be read: {@link #FULL}, {@link #LIMITED} or {@link #NONE}.
	 *
	 * <p>
	 * The same answer {@code photo_manager} gives for images and videos together: from Android 14
	 * on each of the two is full with its own permission and limited with
	 * {@code READ_MEDIA_VISUAL_USER_SELECTED}, and the two together are full only where both are;
	 * on Android 13 both {@code READ_MEDIA_*} permissions are needed; below it
	 * {@code READ_EXTERNAL_STORAGE}.
	 * </p>
	 */
	private static String mediaAccess(Context context) {
		if (Build.VERSION.SDK_INT >= 34) {
			boolean selected = held(context, READ_MEDIA_VISUAL_USER_SELECTED);
			String images = held(context, READ_MEDIA_IMAGES) ? FULL : selected ? LIMITED : NONE;
			String videos = held(context, READ_MEDIA_VIDEO) ? FULL : selected ? LIMITED : NONE;
			if (FULL.equals(images) && FULL.equals(videos)) {
				return FULL;
			}
			if (NONE.equals(images) && NONE.equals(videos)) {
				return NONE;
			}
			return LIMITED;
		}
		if (Build.VERSION.SDK_INT >= 33) {
			return held(context, READ_MEDIA_IMAGES) && held(context, READ_MEDIA_VIDEO) ? FULL : NONE;
		}
		return held(context, Manifest.permission.READ_EXTERNAL_STORAGE) ? FULL : NONE;
	}

	private static boolean held(Context context, String permission) {
		return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
	}

}
