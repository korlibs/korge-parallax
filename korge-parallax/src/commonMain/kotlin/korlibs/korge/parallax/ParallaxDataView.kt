package korlibs.korge.parallax

import korlibs.datastructure.iterators.*
import korlibs.korge.view.*
import korlibs.korge.view.animation.*
import korlibs.image.format.*
import korlibs.math.clamp
import korlibs.time.milliseconds
import korlibs.korge.parallax.ParallaxConfig.Mode.*
import korlibs.math.geom.*


inline fun Container.parallaxDataView(
    data: ParallaxDataContainer,
    size: SizeInt,
    scale: Double = 1.0,
    smoothing: Boolean = false,
    disableScrollingX: Boolean = false,
    disableScrollingY: Boolean = false,
    callback: @ViewDslMarker ParallaxDataView.() -> Unit = {}
): ParallaxDataView = ParallaxDataView(data, size, scale, smoothing, disableScrollingX, disableScrollingY).addTo(this, callback)


/**
 * The ParallaxDataView is a special view object which can be used to show some background layers behind the play
 * field of a game level, menu, intro, extro, etc. It is configured through a data object [ParallaxDataContainer] which
 * stores model data for the view (cf. MVC pattern).
 *
 * The definition of a parallax layer is an image which is (in most cases) repeating in X and/or Y direction.
 * Thus scrolling of the parallax layer means that the parallax layer is moving relative to the scene camera. In the
 * camera view it looks like that the image is moving repeatedly over the screen. By constructing multiple layers which
 * are sitting one over another and which are moving with different speed factors a nice depth effect can be created.
 * That simulates the parallax effect of early 1990 video games.
 *
 * The parallax layers can be configured through a set of data classes which control how the parallax layers are
 * constructed, presented and moved over the screen. The actual image data for a layer is taken from an Aseprite file
 * which follows specific rules. This file must contain at least one frame with a couple of layers with defined names.
 * Also, for the so-called parallax plane the Aseprite file has to contain specific slice objects. An example Aseprite
 * template file is included in Korge's example "parallax-scrolling-aseprite".
 *
 * Types of parallax layers:
 * 1. Background layers
 * 2. parallax plane with attached layers
 * 3. Foreground layers
 *
 * Background layers (1) are drawn first and per definition are shown behind the parallax plane.
 * The parallax plane (2) is a special layer in the Aseprite image which is sliced into stripes of different lengths.
 * These stripes are moving with different speed which increases with more distance to the central vanishing point on
 * the screen. This results in a pseudo 3D plane effect which can be seen in early 1990 video games like Street Fighter 2
 * or Lionheart (Amiga). The parallax plane can have attached layers which are by themselves layers like background
 * layers. The difference is that they are moving depending on their position (top or bottom border) on the parallax
 * plane.
 * Finally, the Foreground layers (3) are drawn and thus are positioned in front of the parallax plane with its attached
 * layers. Any of these layer types can be also kept empty. With a combination of all layer types it is possible to
 * achieve different parallax layer effects.
 *
 * Please see the description of [ParallaxDataContainer] and its sub-data classes [ParallaxConfig],
 * [ParallaxLayerConfig], [ParallaxPlaneConfig] and [ParallaxAttachedLayerConfig] to set up a valid and meaningful
 * configuration for the parallax view.
 *
 * Scrolling of the parallax background can be done by setting [deltaX], [deltaY] and [diagonal] properties.
 * When the mode in [ParallaxConfig] is set to [HORIZONTAL_PLANE] then setting [deltaX] is
 * moving the parallax view in horizontal direction. Additionally, to that the view can be moved vertically by setting
 * [diagonal] property. Its range goes from [0..1] which means it scrolls within its vertical boundaries.
 * This applies analogously to [deltaY] and [diagonal] when mode is set to [VERTICAL_PLANE].
 * Setting mode to [NO_PLANE] means that the parallax plane is disabled and only "normal" layers are used.
 * Then the parallax background can be scrolled endlessly in any direction by setting [deltaX] and [deltaY].
 *
 * Sometimes the parallax view should not scroll automatically but the position should be set directly by altering [x] and [y]
 * of the ParallaxDataView. E.g. when using parallax backgrounds in an intro or similar where it is needed to have more
 * "control" over the movement of the background. Thus, the automatic scrolling can be disabled with [disableScrollingX] and
 * [disableScrollingY] parameters.
 */
class ParallaxDataView(
    data: ParallaxDataContainer,
    size: SizeInt,
    scale: Double = 1.0,
    smoothing: Boolean = false,
    disableScrollingX: Boolean = false,
    disableScrollingY: Boolean = false
) : Container() {

    // Delta movement in X or Y direction of the parallax background depending on the scrolling direction
    var deltaX: Double = 0.0
    var deltaY: Double = 0.0

    // Percentage of the position diagonally to the scrolling direction (only used with parallax plane setup)
    var diagonal: Double = 0.0  // Range: [0...1]

    // Accessing properties of layer objects
    private val layerMap: HashMap<String, View> = HashMap()

    // The middle point of the parallax plane (central vanishing point on the screen)
    private val parallaxPlaneMiddlePoint: Double =
        when (data.config.mode) {
            HORIZONTAL_PLANE -> size.width.toDouble() * 0.5
            VERTICAL_PLANE -> size.height.toDouble() * 0.5
            NO_PLANE -> 0.0  // not used without parallax plane setup
        }

    private val parallaxLayerSize: Int =
        when (data.config.mode) {
            HORIZONTAL_PLANE ->
                (data.backgroundLayers?.height ?: data.foregroundLayers?.height?: data.attachedLayersFront?.height ?: data.attachedLayersRear?.height ?: 0) - (data.config.parallaxPlane?.offset ?: 0)
            VERTICAL_PLANE ->
                (data.backgroundLayers?.width ?: data.foregroundLayers?.width ?: data.attachedLayersFront?.width ?: data.attachedLayersRear?.height ?: 0) - (data.config.parallaxPlane?.offset ?: 0)
            NO_PLANE -> 0  // not used without parallax plane setup
        }

    // Calculate array of speed factors for each line in the parallax plane.
    // The array will contain numbers starting from 1.0 -> 0.0 and then from 0.0 -> 1.0
    // The first part of the array is used as speed factor for the upper / left side of the parallax plane.
    // The second part is used for the lower / right side of the parallax plane.
    private val parallaxPlaneSpeedFactor = FloatArray(
        parallaxLayerSize
    ) { i ->
        val midPoint: Float = parallaxLayerSize * 0.5f
        (data.config.parallaxPlane?.speedFactor ?: 1f) * (
            // The pixel in the point of view must not stand still, they need to move with the lowest possible speed (= 1 / midpoint)
            // Otherwise the midpoint is "running" away over time
            if (i < midPoint)
                1f - (i / midPoint)
            else
                (i - midPoint + 1f) / midPoint
            )
    }

    fun getLayer(name: String): View? = layerMap[name]

    private fun constructParallaxPlane(
        parallaxPlane: ImageDataContainer?,
        attachedLayersFront: ImageData?,
        attachedLayersRear: ImageData?,
        config: ParallaxPlaneConfig?,
        isScrollingHorizontally: Boolean,
        smoothing: Boolean,
        disableScrollingX: Boolean,
        disableScrollingY: Boolean
    ) {
        if (parallaxPlane == null || config == null) return
        if (parallaxPlane.imageDatas[0].frames.isEmpty()) error("Parallax plane not found. Check that name of parallax plane layer in Aseprite matches the name in the parallax config.")
        if (parallaxPlaneSpeedFactor.size < parallaxPlane.imageDatas.size) error("Parallax data must at least contain one layer in backgroundLayers, foregroundLayers or attachedLayers!")

        // Add attached layers which will be below parallax plane
        if (attachedLayersRear != null && config.attachedLayersRear != null) {
            constructAttachedLayers(attachedLayersRear, config.attachedLayersRear, config.selfSpeed, smoothing, isScrollingHorizontally, disableScrollingX, disableScrollingY)
        }

        layerMap[config.name] = container {
            parallaxPlane.imageDatas.fastForEach { data ->
                imageDataViewEx(data, playing = false, smoothing = smoothing, repeating = true) {
                    val layer = getLayer(config.name)
                        ?: error("Could not find parallax plane '${config.name}' in ImageData. Check that name of parallax plane in Aseprite matches the name in the parallax config.")
                    layer as SingleTile
                    if (isScrollingHorizontally) {
                        layer.repeat(repeatX = true)
                        x = parallaxPlaneMiddlePoint
                        val speedFactor = parallaxPlaneSpeedFactor[layer.y.toInt()]
                        // Calculate the offset for the inner scrolling of the layer depending on its y-position
                        if (!disableScrollingX) addUpdater { x += (((deltaX * speedFactor) + (config.selfSpeed * speedFactor)) * it.milliseconds).toFloat() }
                    } else {
                        layer.repeat(repeatY = true)
                        y = parallaxPlaneMiddlePoint
                        val speedFactor = parallaxPlaneSpeedFactor[layer.x.toInt()]
                        // Calculate the offset for the inner scrolling of the layer depending on its x-position
                        if (!disableScrollingY) addUpdater { y += (((deltaY * speedFactor) + (config.selfSpeed * speedFactor)) * it.milliseconds).toFloat() }
                    }
                }
            }
        }

        // Add attached layers which will be on top of parallax plane
        if (attachedLayersFront != null && config.attachedLayersFront != null) {
            constructAttachedLayers(attachedLayersFront, config.attachedLayersFront, config.selfSpeed, smoothing, isScrollingHorizontally, disableScrollingX, disableScrollingY)
        }
    }

    private fun constructAttachedLayers(attachedLayers: ImageData, attachedLayersConfig: List<ParallaxAttachedLayerConfig>, selfSpeed: Float,
                                        smoothing: Boolean, isScrollingHorizontally: Boolean, disableScrollingX: Boolean,
                                        disableScrollingY: Boolean) {
        if (attachedLayers.frames.isEmpty()) error("No attached layers not found. Check that name of attached layers in Aseprite matches the name in the parallax config.")
        val imageData = imageDataViewEx(attachedLayers, playing = false, smoothing = smoothing, repeating = true)

        for (conf in attachedLayersConfig) {
            val layer = imageData.getLayer(conf.name)

            if (layer == null) {
                println("WARNING: Could not find layer '${conf.name}' in ImageData. Check that name of attached layer in Aseprite matches the name in the parallax config.")
            } else layerMap[conf.name] = (layer as SingleTile).apply {
                repeat(repeatX = isScrollingHorizontally && conf.repeat, repeatY = !isScrollingHorizontally && conf.repeat)

                if (!disableScrollingX && isScrollingHorizontally) {
                    // Attach the layer to the position on the parallax plane (either top or bottom border
                    // depending on attachBottomRight config)
                    val speedFactor = parallaxPlaneSpeedFactor[layer.y.toInt() + (layer.height.toInt().takeIf { conf.attachBottomRight } ?: 0)]
                    addUpdater {
                        // Calculate the offset for the inner scrolling of the layer
                        x += (((deltaX * speedFactor) + (selfSpeed * speedFactor)) * it.milliseconds).toFloat()
                    }
                } else if (!disableScrollingY && !isScrollingHorizontally) {
                    val speedFactor = parallaxPlaneSpeedFactor[layer.x.toInt() + (layer.width.toInt().takeIf { conf.attachBottomRight } ?: 0)]
                    addUpdater {
                        // Calculate the offset for the inner scrolling of the layer
                        x += (((deltaY * speedFactor) + (selfSpeed * speedFactor)) * it.milliseconds).toFloat()
                    }
                }
            }
        }
    }

    private fun constructLayer(
        layers: ImageData?,
        config: List<ParallaxLayerConfig>?,
        mode: ParallaxConfig.Mode,
        smoothing: Boolean,
        disableScrollingX: Boolean,
        disableScrollingY: Boolean
    ) {
        if (layers == null || config == null || layers.frames.isEmpty()) return

        val imageData = imageDataViewEx(layers, playing = false, smoothing = smoothing, repeating = true)

        for (conf in config) {
            val layer = imageData.getLayer(conf.name)

            if (layer == null) {
                println("WARNING: Could not find layer '${conf.name}' in ImageData. Check that name of layer in Aseprite matches the name in the parallax config.")
            } else layerMap[conf.name] = (layer as SingleTile).apply {
                repeat(repeatX = conf.repeatX, repeatY = conf.repeatY)

                if (conf.speedFactor != null || conf.selfSpeedX != 0f || conf.selfSpeedY != 0f) {
                    val speedFactor = conf.speedFactor ?: 0f

                    // Do horizontal or vertical movement depending on parallax scrolling direction
                    // Calculate the offset for the inner scrolling of the layer
                    when (mode) {
                        HORIZONTAL_PLANE -> {
                            if (!disableScrollingX) { addUpdater { x += ((deltaX * speedFactor + conf.selfSpeedX) * it.milliseconds).toFloat() } }
                        }
                        VERTICAL_PLANE -> {
                            if (!disableScrollingY) { addUpdater { y += ((deltaY * speedFactor + conf.selfSpeedY) * it.milliseconds).toFloat() } }
                        }
                        NO_PLANE -> {
                            if (!disableScrollingX && !disableScrollingY) {
                                addUpdater {
                                    x += ((deltaX * speedFactor + conf.selfSpeedX) * it.milliseconds).toFloat()
                                    y += ((deltaY * speedFactor + conf.selfSpeedY) * it.milliseconds).toFloat()
                                }
                            }
                            if (!disableScrollingX && disableScrollingY) { addUpdater { x += ((deltaX * speedFactor + conf.selfSpeedX) * it.milliseconds).toFloat() } }
                            if (disableScrollingX && !disableScrollingY) { addUpdater { y += ((deltaY * speedFactor + conf.selfSpeedY) * it.milliseconds).toFloat() } }
                        }
                    }
                }
            }
        }
    }

    init {
        // Only the base container for all view objects needs to be scaled
        this.scale = scale

        // First create background layers in the back
        constructLayer(data.backgroundLayers, data.config.backgroundLayers, data.config.mode, smoothing, disableScrollingX, disableScrollingY)

        // Then construct the two parallax planes with their attached layers
        if (data.config.mode != NO_PLANE) {
            constructParallaxPlane(
                data.parallaxPlane,
                data.attachedLayersFront,
                data.attachedLayersRear,
                data.config.parallaxPlane,
                data.config.mode == HORIZONTAL_PLANE,
                smoothing,
                disableScrollingX,
                disableScrollingY
            )
            // Do horizontal or vertical movement depending on parallax scrolling direction
            if (!disableScrollingY && data.config.mode == HORIZONTAL_PLANE) {
                // Move parallax plane inside borders
                addUpdater {
                    // Sanity check of diagonal movement - it has to be between 0.0 and 1.0
                    diagonal = diagonal.clamp(0.0, 1.0)
                    y = (-(diagonal * (parallaxLayerSize - size.height)))
                }
            } else if (!disableScrollingX && data.config.mode == VERTICAL_PLANE) {
                addUpdater {
                    diagonal = diagonal.clamp(0.0, 1.0)
                    x = (-(diagonal * (parallaxLayerSize - size.width)))
                }
            }
        }

        // Last construct the foreground layers on top
        constructLayer(data.foregroundLayers, data.config.foregroundLayers, data.config.mode, smoothing, disableScrollingX, disableScrollingY)
    }
}
