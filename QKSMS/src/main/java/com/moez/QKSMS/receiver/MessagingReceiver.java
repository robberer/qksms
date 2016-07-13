package com.moez.QKSMS.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.telephony.SmsMessage;
import android.util.Log;
import com.moez.QKSMS.common.BlockedConversationHelper;
import com.moez.QKSMS.common.ConversationPrefsHelper;
import com.moez.QKSMS.common.utils.PackageUtils;
import com.moez.QKSMS.data.Message;
import com.moez.QKSMS.service.NotificationService;
import com.moez.QKSMS.transaction.NotificationManager;
import com.moez.QKSMS.transaction.SmsHelper;
import com.moez.QKSMS.ui.settings.SettingsFragment;
import org.mistergroup.muzutozvednout.ShouldIAnswerBinder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Date;

public class MessagingReceiver extends BroadcastReceiver {
    private final String TAG = "MessagingReceiver";

    private Context mContext;
    private SharedPreferences mPrefs;

    private String mAddress;
    private String mBody;
    private String mDateString;
    private long mDate;

    private Uri mUri;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive");
        abortBroadcast();

        mContext = context;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (intent.getExtras() != null) {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            SmsMessage[] messages = new SmsMessage[pdus.length];
            for (int i = 0; i < messages.length; i++) {
                messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            }

            SmsMessage sms = messages[0];
            if (messages.length == 1 || sms.isReplace()) {
                mBody = sms.getDisplayMessageBody();
            } else {
                StringBuilder bodyText = new StringBuilder();
                for (SmsMessage message : messages) {
                    bodyText.append(message.getMessageBody());
                }
                mBody = bodyText.toString();
            }

            mAddress = sms.getDisplayOriginatingAddress();
            mDate = sms.getTimestampMillis();

            if (mPrefs.getBoolean(SettingsFragment.SHOULD_I_ANSWER, false) &&
                    PackageUtils.isAppInstalled(mContext, "org.mistergroup.muzutozvednout")) {

                ShouldIAnswerBinder shouldIAnswerBinder = new ShouldIAnswerBinder();
                shouldIAnswerBinder.setCallback(new ShouldIAnswerBinder.Callback() {
                    @Override
                    public void onNumberRating(String number, int rating) {
                        Log.i(TAG, "onNumberRating " + number + ": " + String.valueOf(rating));
                        shouldIAnswerBinder.unbind(context.getApplicationContext());
                        if (rating != ShouldIAnswerBinder.RATING_NEGATIVE) {
                            insertMessageAndNotify();
                        }
                    }

                    @Override
                    public void onServiceConnected() {
                        try {
                            shouldIAnswerBinder.getNumberRating(mAddress);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onServiceDisconnected() {
                    }
                });

                shouldIAnswerBinder.bind(context.getApplicationContext());
            } else {
                insertMessageAndNotify();
            }

            /* from here we try to backup received sms to receipt printer
               @robert
               Always append the messages to File "last-sms.txt" as soon as the next sms is received
               then try to print. If we have no network, data is preserved and printed as soon as
               CUPS get reachable withing the next sms. The JFCupsPrint Service then deletes the
               last-sms.txt file. ;-)
             */
            mDateString = (new Date(mDate).toLocaleString()).toString();

            PrintWriter printWriter;
            try {
                printWriter = new PrintWriter(new FileOutputStream(new File("/storage/emulated/legacy/last-sms.txt"),true));
                printWriter.append((char)0x2264 + " " + mAddress + "\n" + mDateString + "\n" + mBody + "\n");
                for(int charCount=1; charCount<21; charCount++){
                    printWriter.append((char) 0x2550);
                }
                printWriter.append("\n");
                printWriter.close();
            } catch (FileNotFoundException fnfe) {
                System.out.println(fnfe);
            }

            Intent sharingIntent = new Intent(Intent.ACTION_SEND);
            sharingIntent.setClassName("com.jonbanjo.cupsprintservice", "com.jonbanjo.cupsprint.PrinterPrintDefaultActivity");
            sharingIntent.putExtra(Intent.EXTRA_TEXT, "printSMS");
            sharingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(sharingIntent);

            /*
                End of @robert Mod
             */
        }
    }

    private void insertMessageAndNotify() {
        mUri = SmsHelper.addMessageToInbox(mContext, mAddress, mBody, mDate);

        Message message = new Message(mContext, mUri);
        ConversationPrefsHelper conversationPrefs = new ConversationPrefsHelper(mContext, message.getThreadId());

        // The user has set messages from this address to be blocked, but we at the time there weren't any
        // messages from them already in the database, so we couldn't block any thread URI. Now that we have one,
        // we can block it, so that the conversation list adapter knows to ignore this thread in the main list
        if (BlockedConversationHelper.isFutureBlocked(mPrefs, mAddress)) {
            BlockedConversationHelper.unblockFutureConversation(mPrefs, mAddress);
            BlockedConversationHelper.blockConversation(mPrefs, message.getThreadId());
            message.markSeen();
            BlockedConversationHelper.FutureBlockedConversationObservable.getInstance().futureBlockedConversationReceived();

            // If we have notifications enabled and this conversation isn't blocked
        } else if (conversationPrefs.getNotificationsEnabled() && !BlockedConversationHelper.getBlockedConversationIds(
                PreferenceManager.getDefaultSharedPreferences(mContext)).contains(message.getThreadId())) {
            Intent messageHandlerIntent = new Intent(mContext, NotificationService.class);
            messageHandlerIntent.putExtra(NotificationService.EXTRA_POPUP, true);
            messageHandlerIntent.putExtra(NotificationService.EXTRA_URI, mUri.toString());
            mContext.startService(messageHandlerIntent);

            UnreadBadgeService.update(mContext);
            NotificationManager.create(mContext);

        } else { // We shouldn't show a notification for this message
            message.markSeen();
        }

        if (conversationPrefs.getWakePhoneEnabled()) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock((PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP), "MessagingReceiver");
            wakeLock.acquire();
            wakeLock.release();
        }
    }
}
