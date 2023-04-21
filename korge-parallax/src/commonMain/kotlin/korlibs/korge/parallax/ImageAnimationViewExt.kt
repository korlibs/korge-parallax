package korlibs.korge.parallax

import korlibs.korge.view.*
import korlibs.korge.view.animation.*
import korlibs.image.bitmap.*
import korlibs.image.format.*

inline fun Container.repeatedImageAnimationView(
    animation: ImageAnimation? = null,
    direction: ImageAnimation.Direction? = null,
    block: @ViewDslMarker ImageAnimationView<SingleTile>.() -> Unit = {}
) = ImageAnimationView(animation, direction) { SingleTile(Bitmaps.transparent) }.addTo(this, block)

// @TODO: Make ImageDataView open in KorGE and make animationView open so we can override it here while keeping the implementation