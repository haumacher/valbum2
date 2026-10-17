package de.haumacher.valbum.sync_notification;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * The notification of a camera-roll upload (issue #300).
 *
 * <p>
 * WorkManager shows the notification of a foreground work when the work starts, with the text it
 * was registered with. What the run then does is only known to the Dart code of that run, so this
 * plugin posts the same notification id again with the progress — which is how Android updates
 * the notification of a foreground service — and removes a notification of a run that was no
 * foreground service once the run is over. It also asks for {@code POST_NOTIFICATIONS}, a runtime
 * permission from Android 13 on; that needs the Activity, the rest needs only the application
 * context, so the engine WorkManager starts for a background run has all of it but the asking.
 * </p>
 */
public class SyncNotificationPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
		PluginRegistry.RequestPermissionsResultListener {

	private static final String CHANNEL = "de.haumacher.valbum/sync_notification";

	/** The request code of the permission request; any value unlikely to clash with another plugin's. */
	private static final int REQUEST_CODE = 0x2300;

	/**
	 * How long a notification stays when nobody updates or removes it any more: a run that was cut
	 * off by the platform must not leave an "uploading" behind forever.
	 */
	private static final long TIMEOUT_MILLIS = 15 * 60 * 1000L;

	private MethodChannel _channel;

	private Context _context;

	private ActivityPluginBinding _binding;

	/** The answer of the permission request in flight, {@code null} while there is none. */
	private MethodChannel.Result _pending;

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
	public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
		_binding = binding;
		binding.addRequestPermissionsResultListener(this);
	}

	@Override
	public void onDetachedFromActivityForConfigChanges() {
		onDetachedFromActivity();
	}

	@Override
	public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
		onAttachedToActivity(binding);
	}

	@Override
	public void onDetachedFromActivity() {
		if (_binding != null) {
			_binding.removeRequestPermissionsResultListener(this);
			_binding = null;
		}
	}

	@Override
	public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		switch (call.method) {
			case "allowed":
				result.success(allowed());
				break;
			case "askable":
				result.success(askable());
				break;
			case "request":
				request(result);
				break;
			case "show":
				show(call);
				result.success(null);
				break;
			case "clear":
				Integer id = call.argument("id");
				if (id != null) {
					manager().cancel(id);
				}
				result.success(null);
				break;
			default:
				result.notImplemented();
		}
	}

	/** Whether the app's notifications are shown. */
	private boolean allowed() {
		return manager().areNotificationsEnabled();
	}

	/** Whether the system can still be asked to show them: Android 13 and newer, not yet granted. */
	private boolean askable() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
			&& _context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED;
	}

	private void request(MethodChannel.Result result) {
		if (!askable()) {
			result.success(allowed());
			return;
		}
		Activity activity = _binding == null ? null : _binding.getActivity();
		if (activity == null || _pending != null) {
			result.success(false);
			return;
		}
		_pending = result;
		activity.requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_CODE);
	}

	@Override
	public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
			@NonNull int[] grantResults) {
		if (requestCode != REQUEST_CODE) {
			return false;
		}
		MethodChannel.Result result = _pending;
		_pending = null;
		if (result != null) {
			result.success(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
		}
		return true;
	}

	private void show(MethodCall call) {
		Integer id = call.argument("id");
		String channelId = call.argument("channel");
		String channelName = call.argument("channelName");
		String title = call.argument("title");
		String text = call.argument("text");
		Integer done = call.argument("done");
		Integer total = call.argument("total");
		if (id == null || channelId == null) {
			return;
		}
		NotificationManager manager = manager();
		Notification.Builder builder;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// The channel WorkManager's notification of the same work was created in; creating it
			// again only updates its name.
			manager.createNotificationChannel(new NotificationChannel(channelId,
				channelName == null ? channelId : channelName, NotificationManager.IMPORTANCE_LOW));
			builder = new Notification.Builder(_context, channelId).setTimeoutAfter(TIMEOUT_MILLIS);
		} else {
			builder = new Notification.Builder(_context).setPriority(Notification.PRIORITY_LOW);
		}
		int max = total == null ? 0 : total;
		int progress = done == null ? 0 : Math.min(done, max);
		builder
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setContentTitle(title)
			.setContentText(text)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(Notification.CATEGORY_PROGRESS)
			.setProgress(max, progress, max == 0);
		PendingIntent open = openApp();
		if (open != null) {
			builder.setContentIntent(open);
		}
		manager.notify(id, builder.build());
	}

	/** Opens the app where the notification is tapped, {@code null} where it has no launcher entry. */
	private PendingIntent openApp() {
		Intent launch = _context.getPackageManager().getLaunchIntentForPackage(_context.getPackageName());
		if (launch == null) {
			return null;
		}
		return PendingIntent.getActivity(_context, 0, launch,
			PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private NotificationManager manager() {
		return (NotificationManager) _context.getSystemService(Context.NOTIFICATION_SERVICE);
	}

}
