package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only declaration. The platform implementation is used at runtime. */
public interface IPackageManager extends IInterface {
    void grantRuntimePermission(String packageName, String permissionName, int userId)
            throws RemoteException;

    abstract class Stub extends Binder implements IPackageManager {
        public static IPackageManager asInterface(IBinder binder) {
            throw new UnsupportedOperationException("Compile-only stub");
        }
    }
}
