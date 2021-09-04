// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.runningapps;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.OsHidden;
import android.system.StructPasswd;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.collection.SparseArrayCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.ipc.IPCUtils;
import io.github.muntashirakon.AppManager.ipc.ps.ProcessEntry;
import io.github.muntashirakon.AppManager.ipc.ps.Ps;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.servermanager.ActivityManagerCompat;
import io.github.muntashirakon.AppManager.servermanager.PackageManagerCompat;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.Utils;

@WorkerThread
public final class ProcessParser {
    private final Context context;
    private final PackageManager pm;
    private HashMap<String, PackageInfo> installedPackages;
    private HashMap<Integer, PackageInfo> installedUids;
    private final HashMap<Integer, ActivityManager.RunningAppProcessInfo> runningAppProcesses = new HashMap<>(50);

    ProcessParser() {
        context = AppManager.getContext();
        pm = AppManager.getContext().getPackageManager();
        getInstalledPackages();
    }

    @VisibleForTesting
    ProcessParser(boolean isUnitTest) {
        if (isUnitTest) {
            installedPackages = new HashMap<>();
            installedUids = new HashMap<>();
            pm = null;
            context = null;
        } else {
            context = AppManager.getContext();
            pm = AppManager.getContext().getPackageManager();
            getInstalledPackages();
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    List<ProcessItem> parse() {
        List<ProcessItem> processItems = new ArrayList<>();
        try {
            List<ProcessEntry> processEntries = (List<ProcessEntry>) IPCUtils.getServiceSafe().getRunningProcesses().getList();
            for (ProcessEntry processEntry : processEntries) {
                if (processEntry.seLinuxPolicy.contains(":kernel:")) continue;
                try {
                    processItems.addAll(parseProcess(processEntry));
                } catch (Exception ignore) {
                }
            }
        } catch (Throwable th) {
            Log.e("ProcessParser", th);
        }
        return processItems;
    }

    @VisibleForTesting
    @NonNull
    HashMap<Integer, ProcessItem> parse(@NonNull File procDir) {
        HashMap<Integer, ProcessItem> processItems = new HashMap<>();
        Ps ps = new Ps(procDir);
        ps.loadProcesses();
        List<ProcessEntry> processEntries = ps.getProcesses();
        for (ProcessEntry processEntry : processEntries) {
            try {
                ProcessItem processItem = parseProcess(processEntry).get(0);
                processItems.put(processItem.pid, processItem);
            } catch (Exception ignore) {
            }
        }
        return processItems;
    }

    @NonNull
    private List<ProcessItem> parseProcess(@NonNull ProcessEntry processEntry) {
        String processName = getSupposedPackageName(processEntry.name);
        List<ProcessItem> processItems = new ArrayList<>(1);
        if (runningAppProcesses.containsKey(processEntry.pid)) {
            //noinspection ConstantConditions
            String[] pkgList = runningAppProcesses.get(processEntry.pid).pkgList;
            if (pkgList != null && pkgList.length > 0) {
                for (String pkgName : pkgList) {
                    @NonNull PackageInfo packageInfo = Objects.requireNonNull(installedPackages.get(pkgName));
                    ProcessItem processItem = new AppProcessItem(processEntry.pid, packageInfo);
                    processItem.name = pm.getApplicationLabel(packageInfo.applicationInfo).toString()
                            + getProcessName(processEntry.name);
                    processItems.add(processItem);
                }
            } else {
                ProcessItem processItem = new ProcessItem(processEntry.pid);
                processItem.name = processEntry.name;
                processItems.add(processItem);
            }
        } else if (installedPackages.containsKey(processName)) {
            @NonNull PackageInfo packageInfo = Objects.requireNonNull(installedPackages.get(processName));
            ProcessItem processItem = new AppProcessItem(processEntry.pid, packageInfo);
            processItem.name = pm.getApplicationLabel(packageInfo.applicationInfo).toString()
                    + getProcessName(processEntry.name);
            processItems.add(processItem);
        } else if (installedUids.containsKey(processEntry.users.fsUid)) {
            @NonNull PackageInfo packageInfo = Objects.requireNonNull(installedUids.get(processEntry.users.fsUid));
            ProcessItem processItem = new AppProcessItem(processEntry.pid, packageInfo);
            processItem.name = pm.getApplicationLabel(packageInfo.applicationInfo).toString() + ":" + processEntry.name;
            processItems.add(processItem);
        } else {
            ProcessItem processItem = new ProcessItem(processEntry.pid);
            processItem.name = processEntry.name;
            processItems.add(processItem);
        }
        for (ProcessItem processItem : processItems) {
            processItem.context = processEntry.seLinuxPolicy;
            processItem.ppid = processEntry.ppid;
            processItem.rss = processEntry.residentSetSize;
            processItem.vsz = processEntry.virtualMemorySize;
            processItem.uid = processEntry.users.fsUid;
            processItem.user = getNameForUid(processEntry.users.fsUid);
            if (context == null) {
                processItem.state = processEntry.processState;
                processItem.state_extra = processEntry.processStatePlus;
            } else {
                processItem.state = context.getString(Utils.getProcessStateName(processEntry.processState));
                processItem.state_extra = context.getString(Utils.getProcessStateExtraName(processEntry.processStatePlus));
            }
        }
        return processItems;
    }

    private void getInstalledPackages() {
        List<PackageInfo> packageInfoList = new ArrayList<>();
        for (int userHandle : Users.getUsersIds()) {
            try {
                packageInfoList.addAll(PackageManagerCompat.getInstalledPackages(0, userHandle));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        installedPackages = new HashMap<>(packageInfoList.size());
        for (PackageInfo info : packageInfoList) {
            installedPackages.put(info.packageName, info);
        }
        installedUids = new HashMap<>(packageInfoList.size());
        List<Integer> duplicateUids = new ArrayList<>();
        for (PackageInfo info : packageInfoList) {
            int uid = info.applicationInfo.uid;
            if (installedUids.containsKey(uid)) {
                // A shared user ID (other way to check user ID will not work since we're only interested in
                // duplicate values)
                duplicateUids.add(uid);
            } else installedUids.put(uid, info);
        }
        // Remove duplicate UIDs as they might create collisions
        for (int uid : duplicateUids) installedUids.remove(uid);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = ActivityManagerCompat.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo info : runningAppProcesses) {
            this.runningAppProcesses.put(info.pid, info);
        }
    }

    private static final SparseArrayCompat<String> uidNameCache = new SparseArrayCompat<>(150);

    @NonNull
    private static String getNameForUid(int uid) {
        String username = uidNameCache.get(uid);
        if (username != null) return username;
        try {
            StructPasswd passwd = OsHidden.getpwuid(uid);
            username = passwd.pw_name;
        } catch (ErrnoException ignored) {
        }
        if (username == null) {
            username = String.valueOf(uid);
        }
        uidNameCache.put(uid, username);
        return username;
    }

    @NonNull
    public static String getSupposedPackageName(@NonNull String processName) {
        if (!processName.contains(":")) {
            return processName;
        }
        int colonIdx = processName.indexOf(':');
        return processName.substring(0, colonIdx);
    }

    @NonNull
    private static String getProcessName(@NonNull String processName) {
        if (!processName.contains(":")) {
            return "";
        }
        int colonIdx = processName.indexOf(':');
        return processName.substring(colonIdx);
    }
}
