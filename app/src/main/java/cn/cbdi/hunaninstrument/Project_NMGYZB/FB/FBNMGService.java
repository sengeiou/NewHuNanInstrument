package cn.cbdi.hunaninstrument.Project_NMGYZB.FB;

import android.app.Service;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.TimeUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.cbdi.hunaninstrument.AppInit;
import cn.cbdi.hunaninstrument.Bean.Employer;
import cn.cbdi.hunaninstrument.Bean.Keeper;
import cn.cbdi.hunaninstrument.Bean.ReUploadBean;
import cn.cbdi.hunaninstrument.Bean.ReUploadWithBsBean;
import cn.cbdi.hunaninstrument.EventBus.AlarmEvent;
import cn.cbdi.hunaninstrument.EventBus.FaceIdentityEvent;
import cn.cbdi.hunaninstrument.EventBus.LockUpEvent;
import cn.cbdi.hunaninstrument.EventBus.NetworkEvent;
import cn.cbdi.hunaninstrument.EventBus.PassEvent;
import cn.cbdi.hunaninstrument.EventBus.TemHumEvent;
import cn.cbdi.hunaninstrument.Retrofit.RetrofitGenerator;
import cn.cbdi.hunaninstrument.State.DoorState.WarehouseDoor;
import cn.cbdi.hunaninstrument.State.LockState.Lock;
import cn.cbdi.hunaninstrument.Tool.SafeCheck;
import cn.cbdi.hunaninstrument.Tool.ServerConnectionUtil;

import cn.cbdi.hunaninstrument.greendao.DaoSession;
import cn.cbdi.hunaninstrument.greendao.ReUploadBeanDao;
import cn.cbdi.hunaninstrument.greendao.ReUploadWithBsBeanDao;
import cn.cbsd.cjyfunctionlib.Func_CJYExtension.Update.SignUtils;
import cn.cbsd.cjyfunctionlib.Func_FaceDetect.presenter.FacePresenter;
import cn.cbsd.cjyfunctionlib.Func_OutputControl.ControlHelper.Door;
import cn.cbsd.cjyfunctionlib.Func_OutputControl.module.IOutputControl;
import cn.cbsd.cjyfunctionlib.Func_OutputControl.presenter.OutputControlPresenter;
import cn.cbsd.cjyfunctionlib.Func_OutputControl.view.IOutputControlView;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FBNMGService extends Service implements IOutputControlView {

    private String TAG = FBNMGService.class.getSimpleName();

    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    SimpleDateFormat url_timeformatter = new SimpleDateFormat("yyyy-MM-dd%20HH:mm:ss");

    HashMap<String, String> paramsMap = new HashMap<String, String>();

    OutputControlPresenter sp = OutputControlPresenter.getInstance();

    private SPUtils config = SPUtils.getInstance("config");

    DaoSession mdaoSession = AppInit.getInstance().getDaoSession();

    ServerConnectionUtil connectionUtil = new ServerConnectionUtil();

    String Last_Value;

    int last_mTemperature = 0;

    int last_mHumidity = 0;

    String THSwitchValue;

    Disposable rx_delay;

    Disposable unlock_noOpen;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("Md5", SignUtils.getSignMd5Str(AppInit.getInstance()));
        sp.SwitchPresenterSetView(this);
        EventBus.getDefault().register(this);
        mapInit();
        syncData();
        reUpload();
        Observable.interval(0, 30, TimeUnit.SECONDS).observeOn(Schedulers.io())
                .subscribe((l) -> testNet());
        Observable.interval(0, AppInit.getInstrumentConfig().getCheckOnlineTime(), TimeUnit.MINUTES)
                .observeOn(Schedulers.io())
                .subscribe((l) -> checkOnline());
        if (AppInit.getInstrumentConfig().isTemHum()) {
            sp.readHum(5, true);
            Observable.interval(10, 3600, TimeUnit.SECONDS).observeOn(Schedulers.io())
                    .subscribe((l) -> StateRecord());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGetPassEvent(PassEvent event) {
        Lock.getInstance().setState(Lock.LockState.STATE_Unlock);
        Lock.getInstance().doNext();
        if(!AppInit.getInstrumentConfig().isHongWai()){
            Observable.timer(120, TimeUnit.SECONDS).subscribeOn(Schedulers.newThread())
                    .subscribe(new Observer<Long>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            unlock_noOpen = d;
                        }

                        @Override
                        public void onNext(Long aLong) {
                            Lock.getInstance().setState(Lock.LockState.STATE_Lockup);
                            sp.buzz(IOutputControl.Hex.H0);
                            EventBus.getDefault().post(new LockUpEvent());
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onComplete() {
                        }
                    });
        }

    }

    @Override
    public void onTemHum(int temperature, int humidity, String THSwitchValue) {
        EventBus.getDefault().post(new TemHumEvent(temperature, humidity));
        if ((Math.abs(temperature - last_mTemperature) > 3 || Math.abs(temperature - last_mTemperature) > 10)) {
            last_mTemperature = temperature;
            last_mHumidity = humidity;
            StateRecord();
        }
        last_mTemperature = temperature;
        last_mHumidity = humidity;
    }


    @Override
    public void onDoorState(Door.DoorState state) {
        if (AppInit.getInstrumentConfig().isHongWai()) {
            if (!WarehouseDoor.getInstance().getMdoorState().equals(state)) {
                WarehouseDoor.getInstance().setMdoorState(state);
                if (state.equals(Door.DoorState.State_Open)) {
                    if (Lock.getInstance().getState().equals(Lock.LockState.STATE_Lockup)) {
                        Lock.getInstance().doNext();
                        alarmRecord();
                    }
                }
            }

        } else {
            if (!WarehouseDoor.getInstance().getMdoorState().equals(state)) {
                if (state.equals(Door.DoorState.State_Open)) {
                    WarehouseDoor.getInstance().setMdoorState(state);
                    WarehouseDoor.getInstance().doNext();
                    if (Lock.getInstance().getState().equals(Lock.LockState.STATE_Lockup)) {
                        alarmRecord();
                    }
                    if (unlock_noOpen != null) {
                        unlock_noOpen.dispose();
                    }
                    if (rx_delay != null) {
                        rx_delay.dispose();
                    }
                } else {
                    WarehouseDoor.getInstance().setMdoorState(state);
                    WarehouseDoor.getInstance().doNext();
                    if (Lock.getInstance().getState().equals(Lock.LockState.STATE_Unlock)) {
                        final String closeDoorTime = TimeUtils.getNowString();
                        Observable.timer(10, TimeUnit.SECONDS).subscribeOn(Schedulers.newThread())
                                .subscribe(new Observer<Long>() {
                                    @Override
                                    public void onSubscribe(Disposable d) {
                                        rx_delay = d;
                                    }

                                    @Override
                                    public void onNext(Long aLong) {
                                        Lock.getInstance().setState(Lock.LockState.STATE_Lockup);
                                        sp.buzz(IOutputControl.Hex.H0);
                                        if (unlock_noOpen != null) {
                                            unlock_noOpen.dispose();
                                        }
                                        CloseDoorRecord(closeDoorTime);
                                        EventBus.getDefault().post(new LockUpEvent());
                                    }

                                    @Override
                                    public void onError(Throwable e) {

                                    }

                                    @Override
                                    public void onComplete() {

                                    }
                                });
                    } else {
                        WarehouseDoor.getInstance().setMdoorState(state);
                    }
                }
            }
        }
    }

    private void reUpload() {
        ReUploadWithBsBeanDao reUploadWithBsBeanDao = mdaoSession.getReUploadWithBsBeanDao();
        List<ReUploadWithBsBean> list = reUploadWithBsBeanDao.queryBuilder().list();
        for (final ReUploadWithBsBean bean : list) {
            if (bean.getContent() != null) {
                if (bean.getType_patrol() != 0) {
                    connectionUtil.post_SingleThread(config.getString("ServerId") + AppInit.getInstrumentConfig().getUpDataPrefix() + bean.getMethod() + "&daid=" + config.getString("daid") + "&checkType=" + bean.getType_patrol(),
                            config.getString("ServerId"), bean.getContent(), new ServerConnectionUtil.Callback() {
                                @Override
                                public void onResponse(String response) {
                                    if (response != null) {
                                        if (response.startsWith("true")) {
                                            Log.e("程序执行记录", "已执行删除" + bean.getMethod());
                                            reUploadWithBsBeanDao.delete(bean);
                                        }
                                    }
                                }
                            });
                } else {
                    connectionUtil.post_SingleThread(config.getString("ServerId") + AppInit.getInstrumentConfig().getUpDataPrefix() + bean.getMethod() + "&daid=" + config.getString("daid"),
                            config.getString("ServerId"), bean.getContent(), new ServerConnectionUtil.Callback() {
                                @Override
                                public void onResponse(String response) {
                                    if (response != null) {
                                        if (response.startsWith("true")) {
                                            Log.e("程序执行记录", "已执行删除" + bean.getMethod());
                                            reUploadWithBsBeanDao.delete(bean);
                                        }
                                    }
                                }
                            });
                }
            } else {
                connectionUtil.post_SingleThread(config.getString("ServerId") + AppInit.getInstrumentConfig().getUpDataPrefix() + bean.getMethod() + "&daid=" + config.getString("daid"),
                        config.getString("ServerId"), new ServerConnectionUtil.Callback() {
                            @Override
                            public void onResponse(String response) {
                                if (response != null) {
                                    if (response.startsWith("true")) {
                                        Log.e("程序执行记录", "已执行删除" + bean.getMethod());
                                        reUploadWithBsBeanDao.delete(bean);
                                    }
                                }
                            }
                        });
            }
        }
        ReUploadBeanDao reUploadBeanDao = mdaoSession.getReUploadBeanDao();
        List<ReUploadBean> list1 = reUploadBeanDao.queryBuilder().list();
        for (final ReUploadBean bean : list1) {
            RetrofitGenerator.getNMGYZBApi().withDataRs(bean.getMethod(), config.getString("key"), bean.getContent())
                    .subscribeOn(Schedulers.single())
                    .unsubscribeOn(Schedulers.single())
                    .observeOn(Schedulers.single())
                    .subscribe(new Observer<String>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull String s) {
                            Log.e("信息提示", bean.getMethod());
                            reUploadBeanDao.delete(bean);


                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            Log.e("信息提示error", bean.getMethod());

                        }

                        @Override
                        public void onComplete() {

                        }
                    });

        }
    }



    private void syncData() {
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "updatePersion");
        map.put("persionType", String.valueOf(3));
        RetrofitGenerator.getNMGYZBApi().GeneralPersionInfo(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        try {
                            mdaoSession.getEmployerDao().deleteAll();
                            String[] idList = s.split("\\|");
                            if (idList.length > 0) {
                                for (String id : idList) {
                                    mdaoSession.insertOrReplace(new Employer(id.toUpperCase(), 3));
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        EventBus.getDefault().post(new FaceIdentityEvent());

                    }

                    @Override
                    public void onComplete() {
                        map.put("persionType", String.valueOf(2));
                        RetrofitGenerator.getNMGYZBApi().GeneralPersionInfo(map)
                                .subscribeOn(Schedulers.io())
                                .unsubscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new Observer<String>() {
                                    @Override
                                    public void onSubscribe(Disposable d) {

                                    }

                                    @Override
                                    public void onNext(String s) {
                                        try {
                                            String[] idList = s.split("\\|");
                                            if (idList.length > 0) {
                                                for (String id : idList) {
                                                    mdaoSession.insertOrReplace(new Employer(id.toUpperCase(), 2));
                                                }
                                            }
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        EventBus.getDefault().post(new FaceIdentityEvent());

                                    }

                                    @Override
                                    public void onComplete() {
                                        map.put("persionType", String.valueOf(1));
                                        RetrofitGenerator.getNMGYZBApi().GeneralPersionInfo(map)
                                                .subscribeOn(Schedulers.io())
                                                .unsubscribeOn(Schedulers.io())
                                                .observeOn(AndroidSchedulers.mainThread())
                                                .subscribe(new Observer<String>() {
                                                    @Override
                                                    public void onSubscribe(Disposable d) {

                                                    }

                                                    @Override
                                                    public void onNext(String s) {
                                                        try {
                                                            String[] idList = s.split("\\|");
                                                            if (idList.length > 0) {
                                                                for (String id : idList) {
                                                                    mdaoSession.insertOrReplace(new Employer(id.toUpperCase(), 1));
                                                                }
                                                            }
                                                        } catch (Exception e) {
                                                            e.printStackTrace();
                                                        }
                                                    }

                                                    @Override
                                                    public void onError(Throwable e) {
                                                        EventBus.getDefault().post(new FaceIdentityEvent());
                                                    }

                                                    @Override
                                                    public void onComplete() {
//                                                        Employer employer1 = new Employer("411222199104206028",1);
//                                                        Employer employer2 = new Employer("44128219830820403X",1);
//                                                        mdaoSession.insertOrReplace(employer1);
//                                                        mdaoSession.insertOrReplace(employer2);
                                                        try {
                                                            List<Keeper> keeperList = mdaoSession.getKeeperDao().loadAll();
                                                            for (Keeper keeper : keeperList) {
                                                                try {
                                                                    mdaoSession.queryRaw(Employer.class, "where CARD_ID = '" + keeper.getCardID() + "'").get(0);
                                                                } catch (IndexOutOfBoundsException e) {
                                                                    mdaoSession.delete(keeper);
                                                                    FacePresenter.getInstance().FaceDeleteByUserName(keeper.getName());
                                                                }
                                                            }
                                                            EventBus.getDefault().post(new FaceIdentityEvent());
                                                        } catch (SQLiteException e) {
                                                            Log.e(TAG, e.toString());
                                                            EventBus.getDefault().post(new FaceIdentityEvent());

                                                        }
                                                    }
                                                });
                                    }
                                });
                    }
                });
    }


    private void mapInit() {
        SafeCheck safeCheck = new SafeCheck();
        safeCheck.setURL(config.getString("ServerId"));
        paramsMap.put("daid", config.getString("daid"));
        paramsMap.put("pass", safeCheck.getPass(config.getString("daid")));
    }

    private void testNet() {
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "test");
        RetrofitGenerator.getNMGYZBApi().GeneralUpdata(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        if (s.startsWith("true")) {
                            EventBus.getDefault().post(new NetworkEvent(true));
                        } else {
                            EventBus.getDefault().post(new NetworkEvent(false));
                        }

                    }

                    @Override
                    public void onError(Throwable e) {
                        EventBus.getDefault().post(new NetworkEvent(false));
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void checkOnline() {
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "checkOnline");
        RetrofitGenerator.getNMGYZBApi().GeneralUpdata(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {


                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void CloseDoorRecord(String time) {
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "closeDoor");
        map.put("time", time);
        RetrofitGenerator.getNMGYZBApi().GeneralUpdata(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        mdaoSession.insert(new ReUploadWithBsBean(null, "dataType=closeDoor" + "&time=" + url_timeformatter.format(new Date(System.currentTimeMillis())), null, 0));

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void alarmRecord() {
        EventBus.getDefault().post(new AlarmEvent());
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "alarm");
        map.put("alarmType", String.valueOf(1));
        map.put("time", formatter.format(new Date(System.currentTimeMillis())));
        RetrofitGenerator.getNMGYZBApi().GeneralUpdata(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        mdaoSession.insert(new ReUploadWithBsBean(null, "dataType=alarm&alarmType=1" + "&time=" + url_timeformatter.format(new Date(System.currentTimeMillis())), null, 0));
                    }

                    @Override
                    public void onComplete() {

                    }
                });

    }

    private void StateRecord() {
        HashMap<String, String> map = (HashMap<String, String>) paramsMap.clone();
        map.put("dataType", "temHum");
        map.put("tem", String.valueOf(last_mTemperature));
        map.put("hum", String.valueOf(last_mHumidity));
        map.put("time", formatter.format(new Date(System.currentTimeMillis())));
        RetrofitGenerator.getNMGYZBApi().GeneralUpdata(map)
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {

                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });

    }
}