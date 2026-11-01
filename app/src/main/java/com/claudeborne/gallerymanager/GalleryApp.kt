package com.claudeborne.gallerymanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class GalleryApp : Application(), ImageLoaderFactory {
    // video decoder lets Coil render video thumbnails like photos
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()
}
