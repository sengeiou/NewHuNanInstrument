package cn.cbdi.hunaninstrument.Config;


import cn.cbdi.hunaninstrument.Project_Hebei.HeBeiService;
import cn.cbsd.cjyfunctionlib.Func_Card.presenter.IDCardPresenter;
import cn.cbsd.cjyfunctionlib.Func_FaceDetect.Module.HuNanFaceImpl;
import cn.cbsd.cjyfunctionlib.Func_FaceDetect.Module.IFace;

public class GDMB_Config extends BaseConfig {

    @Override
    public boolean isFace() {
        return true;
    }

    @Override
    public boolean isTemHum() {
        return true;
    }

    @Override
    public String getPrefix() {
        return "";
    }

    @Override
    public String getDev_prefix() {
        return "800100";
    }

    @Override
    public String getPersonInfoPrefix() {
        return "da_gzmb_persionInfo?";
    }

    @Override
    public String getUpDataPrefix() {
        return "da_gzmb_updata?";
    }

    @Override
    public String getServerId() {
        return "https://gdmb.wxhxp.cn:8009/";
    }

    @Override
    public int getCheckOnlineTime() {
        return 60;
    }

    @Override
    public String getModel() {
        return "CBDI-DA-01";
    }

    @Override
    public String getName() {
        return "防爆采集器";
    }

    @Override
    public String getProject() {
        return "GDMB";        //广东民爆
    }

    @Override
    public String getPower() {
        return "12-18V 2A";
    }

    @Override
    public boolean isCheckTime() {
        return false;
    }

    @Override
    public boolean disAlarm() {
        return true;
    }

    @Override
    public boolean collectBox() {
        return false;
    }

    @Override
    public boolean noise() {
        return false;
    }

    @Override
    public boolean doubleCheck() {
        return true;
    }

    @Override
    public void readCard() {
        IDCardPresenter.getInstance().ReadIC();
        IDCardPresenter.getInstance().ReadID();
    }

    @Override
    public void stopReadCard() {
        IDCardPresenter.getInstance().StopReadID();
        IDCardPresenter.getInstance().StopReadIC();
    }

    @Override
    public boolean fingerprint() {
        return false;
    }

    @Override
    public Class getServiceName() {
        return HeBeiService.class;
    }

    @Override
    public String getMainActivity() {
        return ".Project_Hebei.HebeiMainActivity";
    }

    @Override
    public String getAddActivity() {
        return ".Project_Hebei.HeBeiRegActivity";
    }

    @Override
    public boolean TouchScreen() {
        return false;
    }

    @Override
    public boolean MenKongSuo() {
        return false;
    }

    @Override
    public IFace getFaceImpl() {
        return new HuNanFaceImpl();
    }

    @Override
    public boolean XungengCanOpen() {
        return true;
    }


    @Override
    public boolean DoorMonitorChosen() {
        return false;
    }

    @Override
    public boolean isHongWai() {
        return hongWai;
    }

    @Override
    public void setHongWai(boolean hongWai) {
        this.hongWai= hongWai;

    }

}
