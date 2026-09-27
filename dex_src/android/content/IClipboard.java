package android.content;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;


public interface IClipboard extends IInterface {
    void setPrimaryClip(
            ClipData clip,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    void setPrimaryClipAsPackage(
            ClipData clip,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId,
            String sourcePackage
    );

    void clearPrimaryClip(
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    ClipData getPrimaryClip(
            String pkg,
            String attributionTag,
            int userId,
            int deviceId
    );

    ClipDescription getPrimaryClipDescription(
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    boolean hasPrimaryClip(
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    void addPrimaryClipChangedListener(
            IOnPrimaryClipChangedListener listener,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    void removePrimaryClipChangedListener(
            IOnPrimaryClipChangedListener listener,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    /**
     * Returns true if the clipboard contains text; false otherwise.
     */
    boolean hasClipboardText(
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    String getPrimaryClipSource(
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId
    );

    boolean areClipboardAccessNotificationsEnabledForUser(int userId);

    void setClipboardAccessNotificationsEnabledForUser(boolean enable, int userId);

    abstract class Stub extends Binder implements IClipboard {
        public static IClipboard asInterface(IBinder binder) {
            throw new RuntimeException("IClipboard");
        }
    }
}