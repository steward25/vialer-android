package com.voipgrid.vialer.dialer;

import android.content.Context;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.voipgrid.vialer.R;


/**
 * Custom KeyPadClass to extend the LinearLayout to show dial pad buttons.
 */
public class KeyPadView extends LinearLayout
        implements View.OnClickListener, View.OnLongClickListener {

    public static final int DTMF_TONE_DURATION = 200;

    private OnKeyPadClickListener mListener;
    private ToneGenerator mToneGenerator;

    public KeyPadView(Context context) {
        super(context);
        init();
    }

    public KeyPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KeyPadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        if (!isInEditMode()) {
            AudioManager audioManager = (AudioManager) getContext().getSystemService(
                    Context.AUDIO_SERVICE
            );
            mToneGenerator = new ToneGenerator(
                    AudioManager.STREAM_DTMF,
                    (int) (Math.floor(audioManager.getStreamVolume(AudioManager.STREAM_DTMF) * 5))
            );
        }

        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE
        );
        inflater.inflate(R.layout.view_key_pad, this);

        setUpButtonListeners(findViewById(R.id.dialpad_container));
    }

    /**
     * Recursively set up button listeners for all child DialpadButtons.
     *
     * @param container
     */
    private void setUpButtonListeners(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {

            View child = container.getChildAt(i);

            if (!(child instanceof ViewGroup)) {
                continue;
            }

            if (child instanceof DialpadButton) {
                DialpadButton dialpadButton = (DialpadButton) child;
                if (dialpadButton.getDigit().equals("0")) {
                    dialpadButton.setOnLongClickListener(this);
                }
                dialpadButton.setOnClickListener(this);
                continue;
            }

            setUpButtonListeners((ViewGroup) child);
        }

    }

    public void setOnKeyPadClickListener(OnKeyPadClickListener listener) {
        mListener = listener;
    }

    @Override
    public void onClick(View view) {
        if(view instanceof DialpadButton) {
            DialpadButton button = (DialpadButton) view;
            mToneGenerator.startTone(button.getDtmfTone(), DTMF_TONE_DURATION);
            String digit = button.getDigit();

            if (mListener != null) {
                mListener.onKeyPadButtonClick(digit, button.getChars());
            }
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if(view instanceof DialpadButton) {
            DialpadButton button = (DialpadButton) view;
            String digit = button.getDigit();
            if (mListener != null) {
                if (digit.equals("0")) {
                    mListener.onKeyPadButtonClick(button.getChars(), button.getChars());
                }
            }
        }
        return true;
    }

    public interface OnKeyPadClickListener {
        void onKeyPadButtonClick(String digit, String chars);
    }
}