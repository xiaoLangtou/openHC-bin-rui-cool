package ecarx.xsf.mediacenter;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

/* ECARX 媒体中心 Widget API（照搬 H62 反编译桩，仅保留歌词轮询所需方法） */
public interface IMediaCenterWidgetApiSvc extends IInterface {

    public static abstract class Stub extends Binder implements IMediaCenterWidgetApiSvc {

        private static class Proxy implements IMediaCenterWidgetApiSvc {
            private IBinder mRemote;

            Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return "ecarx.xsf.mediacenter.IMediaCenterWidgetApiSvc";
            }

            @Override // ecarx.xsf.mediacenter.IMediaCenterWidgetApiSvc
            public IMusicPlaybackInfo getMusicPlaybackInfo() throws android.os.RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken("ecarx.xsf.mediacenter.IMediaCenterWidgetApiSvc");
                    this.mRemote.transact(2, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return IMusicPlaybackInfo.Stub.asInterface(parcelObtain2.readStrongBinder());
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }

        public Stub() {
            attachInterface(this, "ecarx.xsf.mediacenter.IMediaCenterWidgetApiSvc");
        }

        public static IMediaCenterWidgetApiSvc asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface query = iBinder.queryLocalInterface("ecarx.xsf.mediacenter.IMediaCenterWidgetApiSvc");
            return (query == null || !(query instanceof IMediaCenterWidgetApiSvc))
                    ? new Proxy(iBinder) : (IMediaCenterWidgetApiSvc) query;
        }
    }

    IMusicPlaybackInfo getMusicPlaybackInfo() throws android.os.RemoteException;
}
