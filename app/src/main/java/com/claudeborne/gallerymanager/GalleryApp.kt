package com.claudeborne.gallerymanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.claudeborne.gallerymanager.data.Settings
import com.claudeborne.gallerymanager.ui.AppLockState

class GalleryApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        AppLockState.start()
    }

    // video decoder lets Coil render video thumbnails like photos
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true) // thumbnails and pictures fade in instead of popping
        .build()
}
