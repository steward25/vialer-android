package com.voipgrid.vialer.sip;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.squareup.okhttp.OkHttpClient;
import com.voipgrid.vialer.CallActivity;
import com.voipgrid.vialer.R;
import com.voipgrid.vialer.api.Registration;
import com.voipgrid.vialer.api.ServiceGenerator;
import com.voipgrid.vialer.api.models.PhoneAccount;
import com.voipgrid.vialer.util.ConnectivityHelper;
import com.voipgrid.vialer.util.Storage;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AudDevManager;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CallSetting;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;
import org.pjsip.pjsua2.pjsua_call_flag;

import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.OkClient;

/**
 * SipService ensures proper lifecycle management for the PJSUA2 library and
 * provides a persistent interface to SIP services throughout the app.
 *
 * Goals of this class:
 * - initiate calls
 * - handle call actions (on speaker, mute, show dialpad, put on hold).
 * - Handle call status (CONNECTED, DISCONNECTED, MEDIA_AVAILABLE, MEDIA_UNAVAILABLE, RINGING_IN, RINGING_OUT).
 */
public class SipService extends Service implements
        AccountStatus,
        CallStatus,
        CallInteraction {

    private final static String TAG = SipService.class.getSimpleName(); // TAG used for debug Logs

    private LocalBroadcastManager mBroadcastManager;

    private Endpoint mEndpoint;

    private SipAccount mSipAccount;

    private ConnectivityHelper mConnectivityHelper;

    private String mToken;

    private String mUrl;

    private String mNumber;

    private String mCallerId;

    private boolean mHasActiveCall = false;

    private String mCallType;

    private Call mCall;

    private boolean mHasHold = false;

    private Handler mHandler;

    private ToneGenerator mToneGenerator;

    private BroadcastReceiver mCallInteractionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Call call = getCurrentCall();
            if(call != null) {
                handleCallInteraction(call, intent);
            }
        }
    };

    private BroadcastReceiver mKeyPadInteractionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleKeyPadInteraction(getCurrentCall(), intent);
        }
    };

    /**
     * SIP does not present Media by default.
     * Use Android's ToneGenerator to play a dial tone at certain required times.
     * @see # for usage of delayed "mRingbackRunnable" callback.
     */
    private Runnable mRingbackRunnable = new Runnable() {
        @Override
        public void run() {
            // Play a ringback tone to update a user that setup is ongoing.
            mToneGenerator.startTone(ToneGenerator.TONE_SUP_DIAL, 1000);
            mHandler.postDelayed(mRingbackRunnable, 4000);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mHandler = new Handler();

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mToneGenerator = new ToneGenerator(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));

        mBroadcastManager = LocalBroadcastManager.getInstance(this);

        mConnectivityHelper = new ConnectivityHelper(
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE),
                (TelephonyManager) getSystemService(TELEPHONY_SERVICE)
        );

        /* Try to load PJSIP library */
        loadPjsip();

        PhoneAccount phoneAccount = new Storage<PhoneAccount>(this).get(PhoneAccount.class);
        if(phoneAccount != null) {

            mEndpoint = createEndpoint(
                    pjsip_transport_type_e.PJSIP_TRANSPORT_UDP,
                    createTransportConfig(getResources().getInteger(R.integer.sip_port))
            );

            AuthCredInfo credInfo = new AuthCredInfo(
                    "digest", "*",
                    phoneAccount.getAccountId(), 0, phoneAccount.getPassword());

            AccountConfig accountConfig = createAccountConfig(
                    SipUri.build(this, phoneAccount.getAccountId()),
                    SipUri.buildRegistrar(this),
                    credInfo
            );
            mSipAccount = createSipAccount(accountConfig,  this, this);

            setupCallInteractionReceiver();
            setupKeyPadInteractionReceiver();
        } else {
            /* User has no Sip Account so service has no function at all. We stop the service */
            broadcast(SipConstants.SIP_SERVICE_HAS_NO_ACCOUNT);
            stopSelf();
        }

    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

        mBroadcastManager.unregisterReceiver(mCallInteractionReceiver);
        mBroadcastManager.unregisterReceiver(mKeyPadInteractionReceiver);

        /* Cleanup SipAccount */
        if(mSipAccount != null) {
            mSipAccount.delete();
            mSipAccount = null;
        }

        /* Cleanup endpoint */
        if(mEndpoint != null) {
            try {
                mEndpoint.libDestroy();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                mEndpoint.delete();
                mEndpoint = null;
            }
        }

        broadcast(SipConstants.SERVICE_STOPPED);

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCallType = intent.getAction();
        Uri number = intent.getData();
        Log.d(TAG, String.format("onStartCommand(%s)", mCallType));
        switch (mCallType) {
            case SipConstants.ACTION_VIALER_INCOMING :
                mUrl = intent.getStringExtra(SipConstants.EXTRA_RESPONSE_URL);
                mToken = intent.getStringExtra(SipConstants.EXTRA_REQUEST_TOKEN);
                mNumber = intent.getStringExtra(SipConstants.EXTRA_PHONE_NUMBER);
                mCallerId = intent.getStringExtra(SipConstants.EXTRA_CONTACT_NAME);
                break;
            case SipConstants.ACTION_VIALER_OUTGOING :
                SipCall call = new SipCall(mSipAccount, this);
                call.setPhoneNumberUri(number);
                call.setCallerId(intent.getStringExtra(SipConstants.EXTRA_CONTACT_NAME));
                call.setPhoneNumber(intent.getStringExtra(SipConstants.EXTRA_PHONE_NUMBER));
                onCallOutgoing(call, number);
                break;
            default: stopSelf(); //no valid action found. No need to keep the service active
        }

        return START_NOT_STICKY;
    }

    private void broadcast(String status) {
        Intent intent = new Intent(SipConstants.ACTION_BROADCAST_CALL_STATUS);
        intent.putExtra(SipConstants.CALL_STATUS_KEY, status);
        mBroadcastManager.sendBroadcast(intent);
    }

    private void setupCallInteractionReceiver() {
        IntentFilter intentFilter = new IntentFilter(SipConstants.ACTION_BROADCAST_CALL_INTERACTION);
        mBroadcastManager.registerReceiver(mCallInteractionReceiver, intentFilter);
    }

    private void setupKeyPadInteractionReceiver() {
        IntentFilter intentFilter = new IntentFilter(SipConstants.ACTION_BROADCAST_KEY_PAD_INTERACTION);
        mBroadcastManager.registerReceiver(mKeyPadInteractionReceiver, intentFilter);
    }

    /** AccountStatus **/
    @Override
    public void onAccountRegistered(Account account, OnRegStateParam param) {
        Log.d(TAG, "onAccountRegistered()");
        if(mCallType.equals(SipConstants.ACTION_VIALER_INCOMING)) {
            Registration registrationApi = ServiceGenerator.createService(
                    mConnectivityHelper,
                    Registration.class,
                    mUrl,
                    new OkClient(new OkHttpClient())
            );

            registrationApi.reply(mToken, new Callback<Object>() {
                @Override
                public void success(Object object, retrofit.client.Response response) {
                /* No need to handle succes callback.
                   Call is automatically routed to onIncomingCall */
                    Log.i(TAG, "Succesfully sent reply to the middleware");
                }

                @Override
                public void failure(RetrofitError error) {
                    Log.d(TAG, "onAccountRegistered() reply failure", error);
                    broadcast(SipConstants.SIP_SERVICE_ACCOUNT_REGISTRATION_FAILED);
                    stopSelf();
                }
            });
        }
    }



    /** AccountStatus **/
    @Override
    public void onAccountUnregistered(Account account, OnRegStateParam param) {
        Log.d(TAG, "onAccountUnregistered()");

    }

    /** AccountStatus **/
    @Override
    public void onAccountInvalidState(Account account, Throwable fault) {
        Log.d(TAG, "onAccountInvalidState()", fault);
    }

    /**
     * Try to load PJSIP library. If the app can not load PJSIP the service stops since it can
     * not use SIP to setup phonecalls.
     *
     * @throws UnsatisfiedLinkError
     */
    private void loadPjsip() {
        /* Try to load PJSIP library */
        try {
            System.loadLibrary("pjsua2");
        } catch (UnsatisfiedLinkError error) { /* Can not load PJSIP library */
            Log.i(TAG, "Cannot load pjsua2 library");
            /* Notify app */
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_LOAD_PJSIP);
            /* Stop the service since the app can not use SIP */
            stopSelf();
        }
    }

    private Endpoint createEndpoint(pjsip_transport_type_e transportType,
                                    TransportConfig transportConfig) {
        Endpoint endpoint = new Endpoint();
        try {
            endpoint.libCreate();
            endpoint.libInit(new EpConfig());
            endpoint.transportCreate(transportType, transportConfig);
            endpoint.libStart();
        } catch (Exception exception) {
            Log.i(TAG, "Cannot start pjsua2 library");
            broadcast(SipConstants.SIP_SERVICE_CAN_NOT_START_PJSIP);
            stopSelf();
        }
        return endpoint;
    }

    private TransportConfig createTransportConfig(long port) {
        TransportConfig config = new TransportConfig();
        config.setPort(port);
        return config;
    }

    private SipAccount createSipAccount(AccountConfig accountConfig, AccountStatus accountStatus,
                                        CallStatus callStatus) {
        SipAccount sipAccount = null;
        try {
            sipAccount = new SipAccount(accountConfig, accountStatus, callStatus);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sipAccount;
    }

    private AccountConfig createAccountConfig(Uri idUri, Uri registrarUri, AuthCredInfo credInfo) {
        AccountConfig config = new AccountConfig();
        config.setIdUri(idUri.toString());
        config.getRegConfig().setRegistrarUri(registrarUri.toString());
        config.getSipConfig().getAuthCreds().add(credInfo);
        return config;
    }

    @Override
    public void onCallIncoming(Call call) {
        Log.d(TAG, "onCallIncoming()");
        CallOpParam callOpParam = new CallOpParam();
        callOpParam.setStatusCode(
                mHasActiveCall ?
                        pjsip_status_code.PJSIP_SC_BUSY_HERE :
                        pjsip_status_code.PJSIP_SC_RINGING
        );
        setCurrentCall(call);
        try {
            call.answer(callOpParam);
            callVisibleForUser(call, CallActivity.TYPE_INCOMING_CALL, mNumber, mCallerId);
        } catch (Exception e) {
            onCallInvalidState(call, e);
        }
    }

    @Override
    public void onCallOutgoing(Call call, Uri number) {
        Log.d(TAG, "onCallOutgoing()");
        CallOpParam callOpParam = new CallOpParam();
        callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
        try {
            call.makeCall(number.toString(), callOpParam);

            setCurrentCall(call);
            callVisibleForUser(call, CallActivity.TYPE_OUTGOING_CALL, number);
        } catch (Exception e) {
            onCallInvalidState(call, e);
        }
    }

    @Override
    public void onCallConnected(Call call) {
        Log.d(TAG, "onCallConnected()");
        broadcast(SipConstants.CALL_CONNECTED_MESSAGE);
    }

    @Override
    public void onCallDisconnected(final Call call) {
        Log.d(TAG, "onCallDisconnected()");
        /* Cleanup the call */
        if(call != null) {
            // Deleting the call leads to inexplicable failures.
        }
        setCurrentCall(null);
        broadcast(SipConstants.CALL_DISCONNECTED_MESSAGE);
        stopSelf();
    }

    @Override
    public void onCallInvalidState(Call call, Throwable fault) {
        Log.e(TAG, "Call in invalid state", fault);
        broadcast(SipConstants.CALL_INVALID_STATE);
        stopSelf();
    }

    @Override
    public void onCallMediaAvailable(Call call, AudioMedia media) {
        Log.d(TAG, "onCallMediaAvailable()");
        try {
            AudDevManager audDevManager = mEndpoint.audDevManager();
            media.startTransmit(audDevManager.getPlaybackDevMedia());
            audDevManager.getCaptureDevMedia().startTransmit(media);

            broadcast(SipConstants.CALL_MEDIA_AVAILABLE_MESSAGE);
        } catch (Exception e) {
            broadcast(SipConstants.CALL_MEDIA_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void onCallMediaUnavailable(Call call) {
        Log.d(TAG, "onCallMediaUnavailable()");
    }

    @Override
    public void onCallStartRingback() {
        Log.d(TAG, "onCallStartRingback()");
        mHandler.postDelayed(mRingbackRunnable, 2000);
    }

    @Override
    public void onCallStopRingback() {
        Log.d(TAG, "onCallStopRingback()");
        mHandler.removeCallbacks(mRingbackRunnable);
    }

    private void callVisibleForUser(Call call, String type, Uri number) {
        Log.d(TAG, "callVisibleForUser()");
        Intent intent = new Intent(this, CallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(number, type);
        if(call instanceof SipCall) {
            SipCall sipCall = (SipCall) call;
            intent.putExtra(CallActivity.CONTACT_NAME, sipCall.getCallerId());
            intent.putExtra(CallActivity.PHONE_NUMBER, sipCall.getPhoneNumber());
        }
        startActivity(intent);
    }

    private void callVisibleForUser(Call call, String type, String number, String callerId) {
        Log.d(TAG, "callVisibleForUser()");
        Intent intent = new Intent(this, CallActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(SipUri.build(this, number), type);
        intent.putExtra(CallActivity.CONTACT_NAME, callerId);
        intent.putExtra(CallActivity.PHONE_NUMBER, number);
        startActivity(intent);
    }

    private void handleCallInteraction(Call call, Intent intent) {
        String type = intent.getStringExtra(SipConstants.CALL_STATUS_ACTION);
        Log.d(TAG, String.format("handleCallInteraction(%s)", type));
        switch (type) {
            case SipConstants.CALL_UPDATE_MICROPHONE_VOLUME_ACTION :
                updateMicrophoneVolume(call,
                        intent.getLongExtra(SipConstants.MICROPHONE_VOLUME_KEY, 1));
                break;
            case SipConstants.CALL_PUT_ON_HOLD_ACTION : putOnHold(call); break;
            case SipConstants.CALL_HANG_UP_ACTION : hangUp(call); break;
            case SipConstants.CALL_PICK_UP_ACTION : answer(call); break;
            case SipConstants.CALL_DECLINE_ACTION : decline(call); break;
            case SipConstants.CALL_XFER_ACTION : xFer(call); break;
        }
    }

    private void handleKeyPadInteraction(Call call, Intent intent) {
        Log.d(TAG, "handleKeyPadInteraction()");
        try {
            call.dialDtmf(intent.getStringExtra(SipConstants.KEY_PAD_DTMF_TONE));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void hangUp(Call call) {
        Log.d(TAG, "hangUp()");
        try {
            CallOpParam callOpParam = new CallOpParam(true);
            callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
            call.hangup(callOpParam);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public void decline(Call call) {
        Log.d(TAG, "decline()");
        hangUp(call);
    }

    @Override
    public void answer(Call call) {
        Log.d(TAG, "answer()");
        CallOpParam callOpParam = new CallOpParam(true);
        callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_ACCEPTED);
        try {
            if(call != null) {
                call.answer(callOpParam);
            }
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public void updateMicrophoneVolume(Call call, long newVolume) {
        try {
            CallMediaInfoVector callMediaInfoVector = call.getInfo().getMedia();
            long size=callMediaInfoVector.size();
            for(int i=0; i<size; i++) {
                CallMediaInfo callMediaInfo = callMediaInfoVector.get(i);
                if(callMediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
                    AudioMedia audioMedia = AudioMedia.typecastFromMedia(call.getMedia(i));
                    audioMedia.adjustRxLevel(newVolume);
                }
            }
        } catch (Exception e) {
            broadcast(SipConstants.CALL_UPDATE_MICROPHONE_VOLUME_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void putOnHold(Call call) {
        Log.d(TAG, "putOnHold()");
        try {
            CallOpParam callOpParam = new CallOpParam(true);
            if(!mHasHold) {
                Log.d(TAG, "call is active");
                call.setHold(callOpParam);
            } else {
                Log.d(TAG, "call is not active");
                CallSetting callSetting = callOpParam.getOpt();
                callSetting.setFlag(pjsua_call_flag.PJSUA_CALL_UNHOLD.swigValue());
                call.reinvite(callOpParam);
            }
            mHasHold = !mHasHold;
        } catch (Exception e) {
            broadcast(SipConstants.CALL_PUT_ON_HOLD_FAILED);
            e.printStackTrace();
        }
    }

    @Override
    public void xFer(Call call) {

    }

    private void setCurrentCall(Call call) {
        mCall = call;
        mHasActiveCall = (call != null);
    }

    private Call getCurrentCall() {
        return mCall;
    }
}
