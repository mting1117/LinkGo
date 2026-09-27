package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only declaration. The platform implementation is used at runtime. */
public interface IConnectivityManager extends IInterface {
    void setFirewallChainEnabled(int chain, boolean enable) throws RemoteException;

    void setUidFirewallRule(int chain, int uid, int rule) throws RemoteException;

    int getUidFirewallRule(int chain, int uid) throws RemoteException;

    abstract class Stub extends Binder implements IConnectivityManager {
        public static IConnectivityManager asInterface(IBinder binder) {
            throw new UnsupportedOperationException("Compile-only stub");
        }
    }
}
