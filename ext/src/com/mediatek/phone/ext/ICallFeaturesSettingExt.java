package com.mediatek.phone.ext;

import android.content.Context;
import android.os.AsyncResult;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;

import com.android.internal.telephony.CommandException;

public interface ICallFeaturesSettingExt {

    /**
     * called when init the preference (onCreate) single card
     * plugin can customize the activity, like add/remove preference screen
     * plugin should check the activiyt class name, to distinct the caller, avoid do wrong work.
     * if (TextUtils.equals(getClass().getSimpleName(), "CallFeaturesSetting") {}
     *
     * @param activity the PreferenceActivity instance
     * @return
     * @internal
     */
    void initOtherCallFeaturesSetting(PreferenceActivity activity);

    /**
     * called when init the preference (onCreate)
     * plugin can customize the fragment, like add/remove preference screen
     * plugin should check the fragment class name, to distinct the caller, avoid do wrong work.
     * if (TextUtils.equals(getClass().getSimpleName(), "CallFeaturesSetting") {}
     *
     * @param fragment the PreferenceFragment instance
     * @internal
     */
    void initOtherCallFeaturesSetting(PreferenceFragment fragment);

    /**
     * Init the call forward option item for C2K.
     * @param activity the activity of the setting preference.
     * @param subId the subId of the setting item.
     * @internal
     */
    void initCdmaCallForwardOptionsActivity(PreferenceActivity activity, int subId);

    /**
     * Need to fire intent to reset IMS PDN connection.
     * @param context the context of the setting preference.
     * @param msg the message to be sent when SS completed.
     * @return
     * @internal
     */
    void resetImsPdnOverSSComplete(Context context, int msg);

    /**
     * For WWWOP, Whether need to show open mobile data dialog or not.
     * @param context the context of the setting preference.
     * @param subId the sudId of the setting item.
     * @return true if need to show it.
     */
    boolean needShowOpenMobileDataDialog(Context context, int subId);

    /**
     * handle preference status when error happens
     * @param preference the preference which error happens on.
     * @internal
     */
    public void onError(Preference preference);

   /**
     * handle error dialog for different errors from framework
     * @param context
     * @param ar
     * @param preference
     * @internal
     */
    public boolean handleErrorDialog(Context context, AsyncResult ar, Preference preference);

    /** Initializes various parameters required.
     * Used in  CallFeatureSettings
     * @param pa PreferenceActivity
     * @param wfcPreference wfc preference
     * @return
     * @internal
     */
    void initPlugin(PreferenceActivity pa, Preference wfcPreference);

    /** Called on events like onResume/onPause etc from WfcSettings.
     * @param event resume/puase etc.
     * @return
     * @internal
     */
    void onCallFeatureSettingsEvent(int event);

    /** get operator specific customized summary for WFC button.
     * Used in CallFeatureSettings
     * @param context context
     * @param defaultSummaryResId default summary res id
     * @return res id of summary to be displayed
     * @internal
     */
    String getWfcSummary(Context context, int defaultSummaryResId);
}
