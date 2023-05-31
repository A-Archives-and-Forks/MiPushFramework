package top.trumeet.mipush.provider.event.type;

import android.content.Context;

import androidx.annotation.Nullable;

import top.trumeet.common.R;
import top.trumeet.mipush.provider.event.Event;
import top.trumeet.mipush.provider.event.EventType;

/**
 * Created by Trumeet on 2018/2/7.
 */

public class RegistrationResultType extends EventType {
    public RegistrationResultType(String mInfo, String pkg, byte[] payload) {
        super(Event.Type.RegistrationResult, mInfo, pkg, payload);
    }

    @Nullable
    @Override
    public CharSequence getSummary(Context context) {
        return context.getString(R.string.event_register_result);
    }
}
