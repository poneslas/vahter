package ru.poneslas.tasya.probe.upload

import android.app.job.JobParameters
import android.app.job.JobService

class UidtJobService : JobService() {
    override fun onStartJob(params: JobParameters?) = false
    override fun onStopJob(params: JobParameters?) = false
}
