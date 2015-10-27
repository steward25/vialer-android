package com.voipgrid.vialer.sip;

import android.net.Uri;
import android.util.Log;

import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.Media;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsua_call_media_status;

/* Regulates SipCall media management and DISCONNECT events. */
class SipCall extends org.pjsip.pjsua2.Call {

    public static final String TAG = SipCall.class.getSimpleName();

    // Callback handler for the onCallState and onCallMediaState events.
    private final CallStatus mCallStatus;

    private Uri mPhoneNumberUri;
    private String mPhoneNumber;
    private String mCallerId;


    /**
     * @param sipAccount the account used to manage this call's authentication.
     * @param callStatus callback object which is used to notify outside world of past events.
     */
    public SipCall(SipAccount sipAccount, CallStatus callStatus) {
        super(sipAccount);
        mCallStatus = callStatus;
    }

    /**
     * @param sipAccount the account used to manage this call's authentication.
     * @param callId the ID of the caller that tries to make a call.
     * @param callStatus callback object which is used to notify of past events.
     */
    public SipCall(SipAccount sipAccount, int callId, CallStatus callStatus) {
        super(sipAccount, callId);
        mCallStatus = callStatus;
    }

    /**
     * Translate the callback to the interface, which is currently implemented by the SipService.
     * @see CallStatus
     *
     * @param onCallStateParam parameters containing the state of an active call.
     */
    @Override
    public void onCallState(OnCallStateParam onCallStateParam) {
        try {
            CallInfo info = getInfo();       /* Check to see if we can get CallInfo with this callback */

            pjsip_inv_state callState = info.getState();
            Log.d(TAG, String.format("onCallState(%s)", callState));
            if (callState == pjsip_inv_state.PJSIP_INV_STATE_CALLING) {
                // This means we're handling an incoming call.
                if (!hasMedia()) { // Based on if we have media.
                    mCallStatus.onCallStartRingback(); // Play a ringback sound.
                } else {
                    mCallStatus.onCallStopRingback();
                }
            } else if (callState == pjsip_inv_state.PJSIP_INV_STATE_CONNECTING
                    || callState == pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
                // We have an incoming call here.
                pjsip_status_code lastStatusCode = info.getLastStatusCode();
                if (lastStatusCode == pjsip_status_code.PJSIP_SC_PROGRESS) {
                    if (hasMedia()) { // This probably means someone gave us media
                        mCallStatus.onCallStopRingback(); // If so stop the ringback
                    }
                } else if (lastStatusCode == pjsip_status_code.PJSIP_SC_OK) {
                    if (hasMedia()) { // The call  setup succeeded.
                        mCallStatus.onCallStopRingback();  // Stop ringback.
                    }
                }
            } else if (callState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                mCallStatus.onCallConnected(this);
            } else if (callState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                mCallStatus.onCallDisconnected(this);
                mCallStatus.onCallStopRingback();
                this.delete();
            }

        } catch (Exception e) {
            e.printStackTrace();
            mCallStatus.onCallInvalidState(this, e);
        }
    }

    /**
     * Translate the callback to the interface, which is implemented by the SipService
     * @see CallStatus
     *
     * @param onCallMediaStateParam parameters containing the state of the an active call' media.
     */
    @Override
    public void onCallMediaState(OnCallMediaStateParam onCallMediaStateParam) {
            /* an (audio) stream is available, find by looping */
        try {
            CallInfo ci = getInfo();
            CallMediaInfoVector media = ci.getMedia();
            // Administration to see if we connected some media to this call.
            boolean mediaAvailable = false;
            for(int i=0; i < media.size(); ++i) {
                CallMediaInfo cmi = media.get(i);
                boolean usableStatus = (cmi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
                        cmi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD);
                if (cmi.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO && usableStatus) {
                    Media m = getMedia(i);
                    AudioMedia audio = AudioMedia.typecastFromMedia(m);
                    mediaAvailable = true; // Do some administration to notify about present media
                    mCallStatus.onCallMediaAvailable(this, audio);
                }
            }
            if (!mediaAvailable) {
                mCallStatus.onCallMediaUnavailable(this);
            }
        } catch (Exception e) {
            mCallStatus.onCallInvalidState(this, e);
        }
    }

    public Uri getPhoneNumberUri() {
        return mPhoneNumberUri;
    }

    public void setPhoneNumberUri(Uri phoneNumberUri) {
        this.mPhoneNumberUri = phoneNumberUri;
    }

    public String getPhoneNumber() {
        return mPhoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.mPhoneNumber = phoneNumber;
    }

    public String getCallerId() {
        return mCallerId;
    }

    public void setCallerId(String callerId) {
        this.mCallerId = callerId;
    }
}
