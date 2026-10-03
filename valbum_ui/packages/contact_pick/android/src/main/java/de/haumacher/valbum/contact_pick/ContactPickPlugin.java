package de.haumacher.valbum.contact_pick;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * Picks one e-mail address or phone number in the phone's own contacts picker (issue #201).
 *
 * <p>
 * {@code Intent.ACTION_PICK} on {@link Email#CONTENT_TYPE} or {@link Phone#CONTENT_TYPE} shows the
 * contacts app's picker of exactly those data rows and answers the URI of the one row picked,
 * with a temporary read grant for that row alone. So the app reads the display name and the
 * address or number from it without {@code READ_CONTACTS}, which it does not declare. An
 * implicit intent started for a result needs no {@code <queries>} entry (package visibility
 * restricts only resolving and querying), so the picker is started without asking whether one
 * exists, and a phone without a contacts app is answered {@code NO_PICKER}.
 * </p>
 */
public class ContactPickPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
		PluginRegistry.ActivityResultListener {

	private static final String CHANNEL = "de.haumacher.valbum/contact_pick";

	private static final String PICK = "pick";

	/** The request code of the picker; any value unlikely to clash with another plugin's. */
	private static final int REQUEST_CODE = 0x2010;

	private MethodChannel _channel;

	private ActivityPluginBinding _binding;

	/** The answer of the pick in flight, {@code null} while there is none. */
	private MethodChannel.Result _pending;

	/** The column holding the picked value, see {@link #onMethodCall}. */
	private String _valueColumn;

	@Override
	public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
		_channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
		_channel.setMethodCallHandler(this);
	}

	@Override
	public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
		_channel.setMethodCallHandler(null);
		_channel = null;
	}

	@Override
	public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
		_binding = binding;
		binding.addActivityResultListener(this);
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
			_binding.removeActivityResultListener(this);
			_binding = null;
		}
	}

	@Override
	public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!PICK.equals(call.method)) {
			result.notImplemented();
			return;
		}
		if (_pending != null) {
			result.error("BUSY", "A contact is being picked already.", null);
			return;
		}
		Activity activity = _binding == null ? null : _binding.getActivity();
		if (activity == null) {
			result.error("NO_ACTIVITY", "The contacts picker needs the app in the foreground.", null);
			return;
		}
		boolean phone = "phone".equals(call.argument("kind"));
		Intent intent = new Intent(Intent.ACTION_PICK);
		intent.setType(phone ? Phone.CONTENT_TYPE : Email.CONTENT_TYPE);
		_valueColumn = phone ? Phone.NUMBER : Email.ADDRESS;
		_pending = result;
		try {
			activity.startActivityForResult(intent, REQUEST_CODE);
		} catch (ActivityNotFoundException ex) {
			_pending = null;
			result.error("NO_PICKER", "This phone has no contacts app to pick from.", null);
		}
	}

	@Override
	public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode != REQUEST_CODE) {
			return false;
		}
		MethodChannel.Result result = _pending;
		_pending = null;
		if (result == null) {
			return true;
		}
		Uri row = data == null ? null : data.getData();
		if (resultCode != Activity.RESULT_OK || row == null || _binding == null) {
			result.success(null);
			return true;
		}
		// The contact's display name, not Email.DISPLAY_NAME, which is the address's own label
		// (data4).
		String[] projection = { ContactsContract.Contacts.DISPLAY_NAME, _valueColumn };
		try (Cursor cursor = _binding.getActivity().getContentResolver().query(row, projection, null, null, null)) {
			if (cursor == null || !cursor.moveToFirst()) {
				result.success(null);
				return true;
			}
			Map<String, Object> answer = new HashMap<>();
			answer.put("name", cursor.isNull(0) ? "" : cursor.getString(0));
			answer.put("value", cursor.isNull(1) ? "" : cursor.getString(1));
			result.success(answer);
		} catch (RuntimeException ex) {
			// A SecurityException where the contacts app granted nothing, or a provider failure.
			result.error("UNREADABLE", "The picked contact could not be read: " + ex.getMessage(), null);
		}
		return true;
	}

}
