package com.device.health.monitor;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class WatchdogJob extends JobService {

    private static final int JOB_ID = 0x1337;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        // Only schedule if not already pending
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) return;
        }
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, WatchdogJob.class))
            .setPeriodic(15 * 60 * 1000L)   // 15 minutes
            .setPersisted(true)              // survives reboots
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false);
        js.schedule(b.build());
        Log.d(SyncService.TAG, "WatchdogJob scheduled");
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (SyncService.getInstance() == null) {
            Log.d(SyncService.TAG, "WatchdogJob: restarting SyncService");
            Intent svc = new Intent(this, SyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
