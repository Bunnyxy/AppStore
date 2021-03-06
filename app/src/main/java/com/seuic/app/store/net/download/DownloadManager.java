package com.seuic.app.store.net.download;

import android.support.v4.util.SimpleArrayMap;

import com.seuic.app.store.AppStoreApplication;
import com.seuic.app.store.bean.AppInfo;
import com.seuic.app.store.bean.DownloadingBean;
import com.seuic.app.store.bean.response.RecommendReceive;
import com.seuic.app.store.greendao.DownloadTaskTable;
import com.seuic.app.store.greendao.GreenDaoManager;
import com.seuic.app.store.greendao.RecommendReceiveTable;
import com.seuic.app.store.cloudservice.InstallAppManager;
import com.seuic.app.store.listener.DownloadCountListener;
import com.seuic.app.store.listener.UpdateCountListener;
import com.seuic.app.store.net.download.task.DownloadPoolManager;
import com.seuic.app.store.net.download.task.DownloadTask;
import com.seuic.app.store.net.download.task.OkHttpDownloader;
import com.seuic.app.store.utils.AppStoreUtils;
import com.seuic.app.store.utils.AppsUtils;
import com.seuic.app.store.utils.FileUtils;
import com.seuic.app.store.utils.HttpHeadUtils;
import com.seuic.app.store.utils.Logger;
import com.seuic.app.store.utils.Md5Utils;

import java.io.File;
import java.util.List;

import okhttp3.OkHttpClient;

import static com.seuic.app.store.net.download.DownloadState.STATE_INSTALL_FAIL;

/**
 * Created on 2017/9/20.
 *
 * @author dpuntu
 *         <p>
 *         多页面同步下载:任意位置获取需要任务Id对应的任务,并对其进行数据更新的监听
 *         <p>
 *         多任务管理
 */
public class DownloadManager {
    /**
     * 正在下载任务集合
     */
    private SimpleArrayMap<String, OkHttpDownloader> mIsDownLoadingMap = new SimpleArrayMap<>();
    /**
     * 任务集合
     */
    private SimpleArrayMap<String, OkHttpDownloader> mOkHttpDownloaderMap = new SimpleArrayMap<>();

    private static DownloadManager mDownloadManager = new DownloadManager();

    private String errorMsg = "error";
    private int errorCode = 1;

    public static DownloadManager getInstance() {
        return mDownloadManager;
    }

    public void setError(int errorCode, String errorMsg) {
        this.errorMsg = errorMsg;
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public int getDownloaderTaskCount() {
        if (mIsDownLoadingMap.size() > 0) {
            return mIsDownLoadingMap.size();
        } else {
            return 0;
        }
    }

    public void setDownLoadState(String taskId, DownloadState state) {
        if (mOkHttpDownloaderMap.containsKey(taskId)) {
            if (state == DownloadState.STATE_NORMAL) {
                mOkHttpDownloaderMap.get(taskId).getDownloadBean().setLoadedLength(0);
            }
            mOkHttpDownloaderMap.get(taskId).getDownloadBean().setLoadState(state);
        } else {
            Logger.e("DownloaderMap don't have this id = " + taskId);
        }
    }

    public SimpleArrayMap<String, OkHttpDownloader> getIsDownLoadingMap() {
        return mIsDownLoadingMap;
    }

    /**
     * 添加所有可见可能被安装的APP到mDownloaderMap，mRecommendReceiveMap，并且不会被移除，除非软件退出后重启
     * <p>
     * 所有的下载必须经过这一步，否则无法下载
     */
    public void add2OkhttpDownloaderMap(RecommendReceive recommendReceive) {
        /**
         * 如果mDownloadingMap中有任务，不需要再添加
         * */
        if (mIsDownLoadingMap.containsKey(recommendReceive.getAppVersionId())
                || mOkHttpDownloaderMap.containsKey(recommendReceive.getAppVersionId())) {
            return;
        }

        /**
         * 保存APP数据到数据库
         * */
        GreenDaoManager.getInstance().insertRecommendReceiveTableDao(recommendReceive);

        mOkHttpDownloaderMap.remove(recommendReceive.getAppVersionId());

        /**
         * 初始化每个APP的状态
         * */
        DownloadState downloadState = DownloadState.STATE_NORMAL;
        List<AppInfo> appInfos = AppsUtils.getAppInfos();
        for (AppInfo info : appInfos) {
            if (recommendReceive.getPackageName().equals(info.getPackageName())) {
                if (!recommendReceive.getAppVersion().equals(info.getAppVersion())) {
                    DownloadTaskTable downloadTaskTable = GreenDaoManager.getInstance().queryDownloadTask(recommendReceive.getAppVersionId());
                    if (downloadTaskTable != null) {
                        downloadState = DownloadState.STATE_PAUSE;
                    } else {
                        downloadState = DownloadState.STATE_UPDATE;
                    }
                } else {
                    downloadState = DownloadState.STATE_INSTALL_SUCCESS;
                }
            } else {
                DownloadTaskTable downloadTaskTable = GreenDaoManager.getInstance().queryDownloadTask(recommendReceive.getAppVersionId());
                if (downloadTaskTable != null) {
                    downloadState = DownloadState.STATE_PAUSE;
                }
            }
        }

        DownloadBean mDownloadBean = new DownloadBean.Builder()
                .downloadUrl(AppStoreUtils.getDownloadUrl(recommendReceive.getDownloadName(), recommendReceive.getPackageName()))
                .fileName(recommendReceive.getAppName() + "_" + recommendReceive.getAppVersion() + "_" + recommendReceive.getDownloadName())
                .loadState(downloadState)
                .savePath(FileUtils.getDownloadPath(AppStoreApplication.getApp()))
                .taskId(recommendReceive.getAppVersionId())
                .headMap(HttpHeadUtils.getHeadMap())
                .build();

        OkHttpDownloader mOkHttpDownloader = new OkHttpDownloader.Builder()
                .addDownloadBean(mDownloadBean)
                .client(new OkHttpClient())
                .keepAliveTime(30)
                .maxTask(50)
                .build();
        /**
         * 保存任务到mOkHttpDownloaderMap
         * */
        mOkHttpDownloaderMap.put(mOkHttpDownloader.getDownloadBean().getTaskId(), mOkHttpDownloader);
    }

    /**
     * 判断当前正在下载的任务中是否有自身
     */
    public boolean iSAppStoreUpdate(RecommendReceive recommendReceive) {
        if (mIsDownLoadingMap.containsKey(recommendReceive.getAppVersionId())) {
            DownloadState downloadState = mIsDownLoadingMap.get(recommendReceive.getAppVersionId()).getDownloadBean().getLoadState();
            if (downloadState == DownloadState.STATE_FINISH
                    || downloadState == DownloadState.STATE_LOADING) {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * 查找任务表mIsDownLoadingMap中是否存在任务
     * <p>
     * 没有的话从可能下载的mDownloaderMap表中导入下载数据
     * <p>
     * 开始下载
     */
    public void start(String taskId) {
        Logger.e("开始任务 " + taskId);
        if (mIsDownLoadingMap.containsKey(taskId)) {
            startSingleTask(mIsDownLoadingMap.get(taskId));
        } else {
            mIsDownLoadingMap.put(taskId, mOkHttpDownloaderMap.get(taskId));
            startSingleTask(mIsDownLoadingMap.get(taskId));
        }
    }

    /**
     * 初始化的时候，从数据库读到数据
     */
    public void checkDownloadingMap() {
        List<DownloadTaskTable> downloadTaskTables = GreenDaoManager.getInstance().queryDownloadTaskTable();
        if (downloadTaskTables == null) {
            return;
        }
        for (DownloadTaskTable downloadTaskTable : downloadTaskTables) {
            RecommendReceiveTable recommendReceiveTable = GreenDaoManager.getInstance().queryRecommendReceive(downloadTaskTable.getTaskId());
            if (recommendReceiveTable == null) {
                continue;
            }
            if (recommendReceiveTable.getPackageName().equals(AppStoreUtils.getAppPackageName())) {
                GreenDaoManager.getInstance().removeDownloadTaskTable(recommendReceiveTable.getAppVersionId());
                continue;
            }
            DownloadingBean mDownloadingBean = new DownloadingBean(recommendReceiveTable.getAppName(),
                                                                   downloadTaskTable.getTaskId(),
                                                                   downloadTaskTable.getLoadedLength(),
                                                                   downloadTaskTable.getTotalSize(),
                                                                   recommendReceiveTable.getAppIconName(),
                                                                   recommendReceiveTable.getPackageName());
            addDownloadingMap(mDownloadingBean);
        }
    }

    /**
     * 添加到下载队列中
     */
    private void addDownloadingMap(DownloadingBean downloadingBean) {
        if (mOkHttpDownloaderMap.containsKey(downloadingBean.getTaskId())) {
            mIsDownLoadingMap.put(downloadingBean.getTaskId(), mOkHttpDownloaderMap.get(downloadingBean.getTaskId()));
        } else {
            RecommendReceiveTable recommendReceiveTable = GreenDaoManager.getInstance().queryRecommendReceive(downloadingBean.getTaskId());
            if (recommendReceiveTable == null) {
                GreenDaoManager.getInstance().removeDownloadTaskTable(downloadingBean.getTaskId());
                return;
            }
            add2OkhttpDownloaderMap(GreenDaoManager.getInstance().table2RecommendReceive(recommendReceiveTable));
            mIsDownLoadingMap.put(downloadingBean.getTaskId(), mOkHttpDownloaderMap.get(downloadingBean.getTaskId()));
        }
        if (mDownloadCountListener != null) {
            mDownloadCountListener.onDownloadCountChange(getDownloaderTaskCount());
        }
    }

    /**
     * 移除APP 方便数据更新
     */
    public void removeAppByPN(String packageName) {
        RecommendReceiveTable mRecommendReceiveTable = GreenDaoManager.getInstance().queryRecommendReceiveByPN(packageName);

        if (mRecommendReceiveTable == null) {
            return;
        }

        String taskId = mRecommendReceiveTable.getAppVersionId();
        // 先提示改变状态
        getDownloadBean(taskId).setLoadState(DownloadState.STATE_NORMAL);
        notifyDownloadUpdate(taskId);
        // 再移除数据
        removeDownloaderById(taskId);
    }

    private void removeDownloaderById(String taskId) {
        GreenDaoManager.getInstance().removeDownloadTaskTable(taskId);
        if (mIsDownLoadingMap.containsKey(taskId)) {
            DownloadPoolManager.getInstance().remove(mIsDownLoadingMap.get(taskId).getDownloadTask());
            mIsDownLoadingMap.remove(taskId);
        }
    }

    public void startAll() {
        if (getDownloaderTaskCount() > 0) {
            for (int i = 0; i < getDownloaderTaskCount(); i++) {
                startSingleTask(mIsDownLoadingMap.get(mIsDownLoadingMap.keyAt(i)));
            }
        } else {
            Logger.e("startAll don't have any task");
        }
    }

    private void startSingleTask(OkHttpDownloader mOkhttpDownloader) {
        DownloadBean bean = mOkhttpDownloader.getDownloadBean();
        String taskId = bean.getTaskId();
        DownloadState state = bean.getLoadState();

        if (mDownloadCountListener != null) {
            mDownloadCountListener.onDownloadCountChange(getDownloaderTaskCount());
        }

        if (state == DownloadState.STATE_NORMAL
                || state == DownloadState.STATE_PAUSE
                || state == DownloadState.STATE_NEWTASK
                || state == DownloadState.STATE_ERROR
                || state == DownloadState.STATE_UPDATE
                || state == DownloadState.STATE_INSTALL_FAIL) {
            DownloadTask downloadTask = mOkhttpDownloader.getDownloadTask();
            downloadTask.setDownloadTask(bean, mOkhttpDownloader.getClient());
            mIsDownLoadingMap.get(taskId).setDownloadTask(downloadTask);
            DownloadPoolManager.getInstance().execute(downloadTask);
            /**
             * 将任务插入到数据库
             * */

            GreenDaoManager.getInstance().insertDownloadTaskTable(bean);
            Logger.d("enqueue download task into thread pool!");
        } else {
            Logger.e("The state of current task is " + bean.getLoadState() + ",  can't be downloaded!");
        }
    }

    /**
     * 根据 taskId 从下载列表中获取下载对象
     *
     * @param taskId
     *         任务ID
     *
     * @return taskId 对应的 DownloadBean
     */
    public DownloadBean getDownloadBean(String taskId) {
        if (mOkHttpDownloaderMap.containsKey(taskId)) {
            return mOkHttpDownloaderMap.get(taskId).getDownloadBean();
        } else {
            return null;
        }
    }

    /**
     * 暂停下载任务
     *
     * @param taskId
     *         被暂停的下载任务 ID
     */
    public void pause(String taskId) {
        DownloadBean bean = getDownloadBean(taskId);
        if (bean != null) {
            bean.setLoadState(DownloadState.STATE_PAUSE);
            notifyDownloadUpdate(bean.getTaskId());
        }
    }

    /**
     * 将所有的任务都暂停
     */
    public void pauseAll() {
        for (int i = 0; i < getDownloaderTaskCount(); i++) {
            pause(mIsDownLoadingMap.keyAt(i));
        }
    }

    /**
     * 移除一项正在下载的任务
     *
     * @param taskId
     *         任务ID
     */
    public void removeLoadingTask(String taskId) {
        if (!mIsDownLoadingMap.containsKey(taskId)) {
            Logger.e("removeLoadingTask don't have " + taskId + " task");
        } else {
            removeTask(taskId);
        }
    }

    /**
     * 数据库移除，loading移除，线程池移除，文件删除，刷新界面
     */
    private void removeTask(String taskId) {
        DownloadBean mDownloadBean = getDownloadBean(taskId);
        mDownloadBean.setLoadState(DownloadState.STATE_INSTALL_FAIL);
        notifyDownloadUpdate(taskId);
    }

    /**
     * 通知所有的监听器更新
     *
     * @param taskId
     *         下载对象ID
     */
    public void notifyDownloadUpdate(String taskId) {
        if (!mIsDownLoadingMap.containsKey(taskId)) {
            Logger.e("notifyDownloadUpdate don't have " + taskId + " task");
        } else {
            DownloadBean mDownloadBean = mIsDownLoadingMap.get(taskId).getDownloadBean();
            // AppStoreNotificationManager.getInstance().showDownloadNotification(mRecommendReceiveMap.get(taskId), mDownloadBean);
            mDownloadBean.notifyObservers(mDownloadBean);
            switch (mDownloadBean.getLoadState()) {
                case STATE_INSTALL_SUCCESS:
                    GreenDaoManager.getInstance().removeCheckUpdateAppsTable(taskId);
                    if (mUpdateCountListener != null) {
                        mUpdateCountListener.onUpdateCountChange();
                    }
                case STATE_INSTALL_FAIL:
                    removeDownloaderById(taskId);
                    break;
                case STATE_FINISH:
                    RecommendReceiveTable mRecommendReceiveTable = GreenDaoManager.getInstance().queryRecommendReceive(taskId);
                    boolean isError;
                    if (mRecommendReceiveTable != null) {
                        File downloadFile = new File(mDownloadBean.getSavePath() + "/" + mDownloadBean.getFileName());
                        if (Md5Utils.getMd5ByFile(downloadFile).equals(mRecommendReceiveTable.getMD5())) {
                            isError = false;
							// 这里使用原生系统安装接口
                            AppsUtils.installApp(downloadFile);
                            // 下面调用CloudService接口, 非专用设备无法使用cloudService
                            //  InstallAppManager.getInstance()
                            //          .addRecommendReceiveMap(
                            //                 taskId,
                            //                 GreenDaoManager.getInstance().table2RecommendReceive(mRecommendReceiveTable));
                        } else {
                            isError = true;
                        }
                    } else {
                        isError = true;
                    }

                    if (isError) {
                        mDownloadBean.setLoadState(STATE_INSTALL_FAIL);
                        notifyDownloadUpdate(taskId);
                    }
                    break;
                default:
                    break;
            }
            if (mDownloadCountListener != null) {
                mDownloadCountListener.onDownloadCountChange(getDownloaderTaskCount());
            }
        }
    }

    /**
     * 注册监听器
     *
     * @param taskId
     *         下载对象ID
     */
    public void registerObserver(String taskId, DownloadObserver observer) {
        if (!mOkHttpDownloaderMap.containsKey(taskId)) {
            Logger.e("registerObserver don't have " + taskId + " task");
        } else {
            mOkHttpDownloaderMap.get(taskId).getDownloadBean().registerObserver(observer);
        }
    }

    /**
     * 移除监听器
     *
     * @param taskId
     *         下载对象ID
     */
    public void removeObserver(String taskId, DownloadObserver observer) {
        if (!mOkHttpDownloaderMap.containsKey(taskId)) {
            Logger.e("removeObserver don't have " + taskId + " task");
        } else {
            mOkHttpDownloaderMap.get(taskId).getDownloadBean().removeObserver(observer);
        }
    }

    private DownloadCountListener mDownloadCountListener;
    private UpdateCountListener mUpdateCountListener;

    /**
     * 当前下载任务的数量
     */
    public void setDownloadCountListener(DownloadCountListener mDownloadCountListener) {
        this.mDownloadCountListener = mDownloadCountListener;
    }

    /**
     * 当前更新任务的数量
     */
    public void setUpdateCountListener(UpdateCountListener mUpdateCountListener) {
        this.mUpdateCountListener = mUpdateCountListener;
    }
}
