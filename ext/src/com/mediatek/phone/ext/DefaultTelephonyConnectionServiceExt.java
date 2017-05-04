package com.mediatek.phone.ext;

import android.content.Context;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SubscriptionController;

import com.mediatek.common.MPlugin;
import com.mediatek.common.PluginImpl;
import com.mediatek.common.telephony.ILteDataOnlyController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
/**
 * Telephony connection service extension plugin for op09.
*/
public class DefaultTelephonyConnectionServiceExt implements ITelephonyConnectionServiceExt {
    /**
     * Check courrent mode is 4G data only mode.
     *
     * @param context from telephony connection service.
     * @param phone is call via by user
     * @return true if in 4G data only mode.
     */
     public boolean isDataOnlyMode(Context context, Phone phone) {
        //Log.d("context : " + context + " phone : " + phone);
        if (null == context || null == phone) {
            return false;
        }
        int state = phone.getServiceState().getState();
        int slotId = SubscriptionController.getInstance().getSlotId(phone.getSubId());
        int svlteSlotId = SvlteModeController.getActiveSvlteModeSlotId();
        Log.d("DefaultTelephonyConnectionServiceExt", "isDataOnlyMode, slotId:" + slotId
                + ", svlteSlotId:" + svlteSlotId + ", state:" + state);
        ILteDataOnlyController lteDataOnlyController = MPlugin.createInstance(
                ILteDataOnlyController.class.getName(), context);
        if (slotId == svlteSlotId && lteDataOnlyController != null
                && !lteDataOnlyController.checkPermission(phone.getSubId())) {
            return true;
        }
        return false;
    }
}
