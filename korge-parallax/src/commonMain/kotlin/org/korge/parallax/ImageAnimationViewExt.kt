package org.korge.parallax

import com.soywiz.korge.view.*
import com.soywiz.korge.view.animation.*
import com.soywiz.korge.view.tiles.SingleTile
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.format.*

inline fun Container.repeatedImageAnimationView(
    animation: ImageAnimation? = null,
    direction: ImageAnimation.Direction? = null,
    block: @ViewDslMarker ImageAnimationView<SingleTile>.() -> Unit = {}
) = ImageAnimationView(animation, direction) { SingleTile(Bitmaps.transparent) }.addTo(this, block)
