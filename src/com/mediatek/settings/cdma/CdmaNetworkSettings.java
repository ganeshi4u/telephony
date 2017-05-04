package com.mediatek.settings.cdma;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.phone.MobileNetworkSettings;
import com.android.phone.R;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.CardType;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.internal.telephony.ltedc.LteDcPhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;
import com.mediatek.phone.ext.ExtensionManager;
import com.mediatek.phone.ext.IMobileNetworkSettingsExt;
import com.mediatek.settings.cdma.TelephonyUtilsEx;
import com.mediatek.telephony.TelephonyManagerEx;

/**
 * CDMA network setting features.
 */
public class CdmaNetworkSettings {

    private static final String TAG = "CdmaNetworkSettings";
    private static final String SINGLE_LTE_DATA = "single_lte_data";
    /// The preference indicated which network mode does user wanted.
    private static final String ENABLE_4G_DATA = "enable_4g_data";
    private static final String ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_4G_LTE_KEY = "enhanced_4g_lte";
    private SwitchPreference mDataOnlyPreference;
    private SwitchPreference mEnable4GDataPreference;
    private Phone mPhone;
    private PreferenceActivity mActivity;
    private int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // SVLTE support system property
    public static final String PROPERTY_CAPABILITY_SIM = "persist.radio.simswitch";
    public static final String MTK_C2K_SLOT2_SUPPORT = "ro.mtk.c2k.slot2.support";

    private boolean mIsLTECardType;

    private IntentFilter mIntentFilter;
    // Fix CR ALPS02054770
    private static boolean sIsSwitching = false;
    private static final String INTENT_ACTION_FINISH_SWITCH_SVLTE_RAT_MODE =
        "com.mediatek.intent.action.FINISH_SWITCH_SVLTE_RAT_MODE";
    private static final String INTENT_ACTION_CARD_TYPE =
        "android.intent.action.CDMA_CARD_TYPE";

    private PhoneStateListener mPhoneStateListener;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "on receive broadcast action = " + action);

            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, 0);
                if (slotId == SvlteModeController.getActiveSvlteModeSlotId()) {
                    updateSwitch();
                }
                return;
            } else if (INTENT_ACTION_FINISH_SWITCH_SVLTE_RAT_MODE.equals(action)) {
                int svlteMode = intent.getIntExtra(SvlteRatController.EXTRA_SVLTE_RAT_MODE, -1);
                Log.d(TAG, "svlteMode = " + svlteMode);
                // If finished broadcast extra svlte mode is the same with
                // current database saved mode, it means switch done.
                if (svlteMode == getCDMARatMode()) {
                    sIsSwitching = false;
                }
            } else if (TelephonyIntents.ACTION_CDMA_CARD_TYPE.equals(intent.getAction())) {
                CardType cardType = (CardType)
                intent.getExtra(TelephonyIntents.INTENT_KEY_CDMA_CARD_TYPE);
                if (cardType.equals(IccCardConstants.CardType.CT_4G_UICC_CARD)) {
                    mIsLTECardType = true;
                } else {
                    mIsLTECardType = false;
                }
                Log.i(TAG, "intent cardType = " + cardType.toString()
                        + ", isLTECardType " + mIsLTECardType);
            }
            // If ACTION_AIRPLANE_MODE_CHANGED received, we just update the switch.
            updateSwitch();
        }
    };

    private ContentObserver mDataConnectionObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.d(TAG, "onChange selfChange=" + selfChange);
            if (!selfChange) {
                updateSwitch();
            }
        }
    };

    /**
     * Constructor.
     * @param activity the activity of the preference.
     * @param preference the preference to be used.
     * @param phone the phone object.
     */
    public CdmaNetworkSettings(PreferenceActivity activity, PreferenceScreen prefS, Phone phone) {
        mActivity = activity;
        mPhone = phone;
        mSubId = phone.getSubId();

        /// M: remove GSM items @{
        if (prefS.findPreference(MobileNetworkSettings.BUTTON_ENABLED_NETWORKS_KEY) != null) {
            prefS.removePreference(prefS.findPreference(
                    MobileNetworkSettings.BUTTON_ENABLED_NETWORKS_KEY));
        }
        if (prefS.findPreference(MobileNetworkSettings.BUTTON_PREFERED_NETWORK_MODE) != null) {
            prefS.removePreference(prefS.findPreference(
                    MobileNetworkSettings.BUTTON_PREFERED_NETWORK_MODE));
        }
        // When in roaming, we still need show the PLMN item.
        if (!TelephonyUtilsEx.isCTRoaming(mPhone) && prefS.findPreference(
                MobileNetworkSettings.BUTTON_PLMN_LIST) != null) {
            prefS.removePreference(
                    prefS.findPreference(MobileNetworkSettings.BUTTON_PLMN_LIST));
        }
        /// @}

        addCdmaSettingsItem(activity, phone);
        register();
    }

    /**
     * Need to add two more items for CDMA OM project:
     * 1. Enable 4G network
     * 2. Enable 4G only
     */
    private void addCdmaSettingsItem(PreferenceActivity activity, Phone phone) {
        /// add TDD data only feature
        Log.d(TAG, "addCdmaSettingsItem");
        mIsLTECardType = TelephonyUtilsEx.isLTE(mPhone);
        addEnable4GNetworkItem(activity);
        addDataOnlyItem(activity);
        updateSwitch();
    }

    private void addDataOnlyItem(PreferenceActivity activity) {
        mDataOnlyPreference = new SwitchPreference(activity);
        mDataOnlyPreference.setTitle(activity.getString(R.string.only_use_LTE_data));
        mDataOnlyPreference.setKey(SINGLE_LTE_DATA);
        mDataOnlyPreference.setSummaryOn(activity.getString(R.string.only_use_LTE_data_summary));
        mDataOnlyPreference.setSummaryOff(activity.getString(R.string.only_use_LTE_data_summary));
        mDataOnlyPreference.setOrder(
                activity.getPreferenceScreen().findPreference(ENABLE_4G_DATA).getOrder() + 1);
        activity.getPreferenceScreen().addPreference(mDataOnlyPreference);
    }

    private void addEnable4GNetworkItem(PreferenceActivity activity) {
        IMobileNetworkSettingsExt ext = ExtensionManager.getMobileNetworkSettingsExt();
        if (mEnable4GDataPreference == null) {
            mEnable4GDataPreference = new SwitchPreference(activity);
            mEnable4GDataPreference.setTitle(R.string.enable_4G_data);
            mEnable4GDataPreference.setKey(ENABLE_4G_DATA);
            mEnable4GDataPreference.setSummary(R.string.enable_4G_data_summary);
            Preference pref = activity.getPreferenceScreen().findPreference(ROAMING_KEY);
            if (pref != null) {
                mEnable4GDataPreference.setOrder(pref.getOrder() + 1);
            }
        }
        activity.getPreferenceScreen().addPreference(mEnable4GDataPreference);
    }

    private void updateSwitch() {
        int ratMode = getCDMARatMode();
        boolean enable = (isLteCardReady() && !sIsSwitching && isCapabilityPhone())
                          || CdmaFeatureOptionUtils.isCTLteTddTestSupport();
        Log.d(TAG, "sIsSwitching = " + sIsSwitching + " ratMode = " + ratMode
              + " enable = " + enable);
        mEnable4GDataPreference.setEnabled(enable);
        mEnable4GDataPreference.setChecked(
                enable && ratMode != TelephonyManagerEx.SVLTE_RAT_MODE_3G);

        /// if data close && default data is not sim one, tdd data only can not choose
        boolean dataEnable = TelephonyManager.getDefault().getDataEnabled();
        int slotId = SubscriptionManager.getSlotId(SubscriptionManager.getDefaultDataSubId());
        boolean isCTEnableData = dataEnable &&
                    (SvlteModeController.getActiveSvlteModeSlotId() == slotId);
        boolean dataOnlyCommon = enable && !TelephonyUtilsEx.isCTRoaming(mPhone) &&
                    ratMode != TelephonyManagerEx.SVLTE_RAT_MODE_3G;
        boolean dataOnlyEnabled = dataOnlyCommon && isCTEnableData;
        Log.d(TAG, "updateSwitch dataOnlyCommon = " + dataOnlyCommon
              + " dataEnable = " + dataEnable);
        mDataOnlyPreference.setEnabled(dataOnlyEnabled ||
                    CdmaFeatureOptionUtils.isCTLteTddTestSupport());
        mDataOnlyPreference.setChecked(dataOnlyCommon &&
                    ratMode == TelephonyManagerEx.SVLTE_RAT_MODE_4G_DATA_ONLY);
    }

    private boolean isLteCardReady() {
        boolean simInserted = TelephonyUtilsEx.isSvlteSlotInserted();
        boolean airPlaneMode = TelephonyUtilsEx.isAirPlaneMode();
        boolean callStateIdle = isCallStateIDLE();
        boolean simStateIsReady = isSimStateReady(
                mActivity, SvlteModeController.getActiveSvlteModeSlotId());
        boolean isReady = mIsLTECardType && simInserted
                && !airPlaneMode && callStateIdle && simStateIsReady;

        Log.d(TAG,"isLTECardType = " + mIsLTECardType + " simInserted = " + simInserted +
                  " airPlaneMode = " + airPlaneMode + " callStateIdle = " + callStateIdle +
                  " simStateIsReady = " + simStateIsReady + " isReady = " + isReady);
        return isReady;
    }

    private int getCDMARatMode() {
        int mode = Settings.Global.getInt(mActivity.getContentResolver(),
                TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId),
                TelephonyManagerEx.SVLTE_RAT_MODE_4G);
        Log.d(TAG, "getCDMARatMode mode = " + mode);
        return mode;
    }

    private boolean isCallStateIDLE() {
        TelephonyManager telephonyManager =
            (TelephonyManager) mActivity.getSystemService(Context.TELEPHONY_SERVICE);
        int currPhoneCallState = telephonyManager.getCallState();
        Log.i(TAG, "mobile isCallStateIDLE = " +
                (currPhoneCallState == TelephonyManager.CALL_STATE_IDLE));
        return currPhoneCallState == TelephonyManager.CALL_STATE_IDLE;
    }

    private void register() {
        mIntentFilter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mIntentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mIntentFilter.addAction(INTENT_ACTION_FINISH_SWITCH_SVLTE_RAT_MODE);
        mIntentFilter.addAction(INTENT_ACTION_CARD_TYPE);
        mActivity.registerReceiver(mReceiver, mIntentFilter);
        Log.d(TAG, "registerReceiver:" + mReceiver);
        mActivity.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId)),
                true, mDataConnectionObserver);

        mPhoneStateListener = new PhoneStateListener(mSubId) {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                super.onCallStateChanged(state, incomingNumber);
                Log.d(TAG, "PhoneStateListener, onCallStateChanged new state=" + state);
                updateSwitch();
            }

            @Override
            public void onDataConnectionStateChanged(int state) {
                super.onDataConnectionStateChanged(state);
                Log.d(TAG, "PhoneStateListener, onDataConnectionStateChanged new state=" + state);
                updateSwitch();
            }
        };
        TelephonyManager.from(mActivity).listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE
                | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
    }

    /**
     * Handle the onResume event.
     */
    public void onResume() {
        Log.d(TAG, "onResume");
        updateSwitch();
    }

    /**
     * Handle the onDestroy event.
     */
    public void onDestroy() {
        Log.d(TAG, "unregisterReceiver:" + mReceiver);
        mActivity.unregisterReceiver(mReceiver);
        mActivity.getContentResolver().unregisterContentObserver(mDataConnectionObserver);
        TelephonyManager.from(mActivity).listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

    }

    /**
     * Reset the state.
     */
    public void resetState() {
        //Reset the value in case quit the mobilenetwork settings
        sIsSwitching = false;
    }

    /**
     * Handle the preference item click event.
     * @param preferenceScreen the preference screen.
     * @param preference the clicked preference object.
     * @return true if the event is handled.
     */
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        if (preference.getKey().equals(SINGLE_LTE_DATA)) {
            handleDataOnlyClick(preference);
            return true;
        } else if (preference.getKey().equals(ENABLE_4G_DATA)) {
            if (CdmaFeatureOptionUtils.isCT6MSupport()) {
                handleEnable4GDataClickForCT(preference);
            } else {
                handleEnable4GDataClick(preference);
            }
            return true;
        }
        return false;
    }

    private void handleEnable4GDataClick(Preference preference) {
        SwitchPreference switchPre = (SwitchPreference) preference;
        boolean isChecked = switchPre.isChecked();
        int ratMode = isChecked ? TelephonyManagerEx.SVLTE_RAT_MODE_4G :
                               TelephonyManagerEx.SVLTE_RAT_MODE_3G;
        Log.d(TAG, "isChecked = " + isChecked + " ratMode = " + ratMode);
        Settings.Global.putInt(mActivity.getContentResolver(),
                TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId), ratMode);
        int networkType = isChecked ?
                (SvlteRatController.RAT_MODE_SVLTE_2G
                 | SvlteRatController.RAT_MODE_SVLTE_3G
                 | SvlteRatController.RAT_MODE_SVLTE_4G)
                 : (SvlteRatController.RAT_MODE_SVLTE_2G
                    | SvlteRatController.RAT_MODE_SVLTE_3G);
        switchSvlte(networkType);
        sIsSwitching = true;
        updateSwitch();
    }

    /*
     * Enable 4G the rat mode will be 2/3/4G, turn off enable 4G will be only 2/3G
     * 2/3/4G :
     * 2/3G :
     */
    private void switchSvlte(int networkType) {
        Log.d(TAG, "value = " + networkType);
        LteDcPhoneProxy lteDcPhoneProxy = (LteDcPhoneProxy) mPhone;
        lteDcPhoneProxy.getSvlteRatController().setRadioTechnology(networkType, null);
    }

    private void handleDataOnlyClick(Preference preference) {
        SwitchPreference swp = (SwitchPreference) preference;
        boolean isChecked = swp.isChecked();
        Log.i(TAG, "handleDataOnlyClick isChecked = " + isChecked);
        Settings.Global.putInt(mActivity.getContentResolver(),
                TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId),
                (isChecked ? TelephonyManagerEx.SVLTE_RAT_MODE_4G_DATA_ONLY
                        : TelephonyManagerEx.SVLTE_RAT_MODE_4G));
        int networkType = isChecked ? SvlteRatController.RAT_MODE_SVLTE_4G
                : (SvlteRatController.RAT_MODE_SVLTE_2G
                   | SvlteRatController.RAT_MODE_SVLTE_3G
                   | SvlteRatController.RAT_MODE_SVLTE_4G);
        switchSvlte(networkType);
        sIsSwitching = true;
        updateSwitch();
    }

    /**
     * judge if sim state is ready.
     * sim state:SIM_STATE_UNKNOWN = 0;SIM_STATE_ABSENT = 1
     * SIM_STATE_PIN_REQUIRED = 2;SIM_STATE_PUK_REQUIRED = 3;
     * SIM_STATE_NETWORK_LOCKED = 4;SIM_STATE_READY = 5;
     * SIM_STATE_CARD_IO_ERROR = 6;
     * @param context Context
     * @param simId sim id
     * @return true if is SIM_STATE_READY
     */
    static boolean isSimStateReady(Context context, int simId) {
        int simState = TelephonyManager.from(context).getSimState(simId);
        Log.i(TAG, "isSimStateReady simState=" + simState);
        return simState == TelephonyManager.SIM_STATE_READY;
    }

    /**
     * Check if phone has 4G capability.
     */
    private boolean isCapabilityPhone() {
        boolean result = TelephonyUtilsEx.getMainPhoneId() == mPhone.getPhoneId();
        Log.d(TAG, "isCapabilityPhone result = " + result
                + " phoneId = " + mPhone.getPhoneId());
        return result;
    }
    /**
    * Add for CT 6M, check if phone need to enable 4G data only when user enable 4G.
    */
    private void handleEnable4GDataClickForCT(Preference preference) {
        final String modeState = "mode_status";
        final String mode = "ltetdd_cdma";
        SwitchPreference switchPre = (SwitchPreference) preference;
        boolean isChecked = switchPre.isChecked();
        int ratMode = isChecked ? TelephonyManagerEx.SVLTE_RAT_MODE_4G :
                               TelephonyManagerEx.SVLTE_RAT_MODE_3G;
        int networkType = isChecked ?
                (SvlteRatController.RAT_MODE_SVLTE_2G
                 | SvlteRatController.RAT_MODE_SVLTE_3G
                 | SvlteRatController.RAT_MODE_SVLTE_4G)
                 : (SvlteRatController.RAT_MODE_SVLTE_2G
                    | SvlteRatController.RAT_MODE_SVLTE_3G);
        Log.d(TAG, "CT6M isChecked = " + isChecked + " ratMode = " + ratMode);

        if (!isChecked) {
            int lastMode = Settings.Global.getInt(mActivity.getContentResolver(),
                    TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId), -1);
            mActivity.getSharedPreferences(modeState, Context.MODE_PRIVATE)
                .edit().putInt(mode, lastMode).commit();
            Log.d(TAG, "handleEnable4GDataClickForCT lastMode = " + lastMode);
        } else {
            int lteCdma = mActivity.getSharedPreferences(modeState, Context.MODE_PRIVATE)
                .getInt(mode, -1);
            Log.d(TAG, "handleEnable4GDataClickForCT lteCdma = " + lteCdma);
            if (lteCdma != -1) {
                ratMode = lteCdma;
                if (lteCdma == TelephonyManagerEx.SVLTE_RAT_MODE_4G_DATA_ONLY) {
                    networkType = SvlteRatController.RAT_MODE_SVLTE_4G;
                }
                mActivity.getSharedPreferences(modeState, Context.MODE_PRIVATE)
                    .edit().putInt(mode, -1).commit();
            }
        }

        Settings.Global.putInt(mActivity.getContentResolver(),
                TelephonyManagerEx.getDefault().getCdmaRatModeKey(mSubId), ratMode);
        switchSvlte(networkType);
        sIsSwitching = true;
        updateSwitch();
    }

}
