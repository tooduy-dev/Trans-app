package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.ComicPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_STEP = 0.5f

/**
 * Interactive Comic Viewer Component:
 * - 100% original unmodified image display with strict viewport clipping (no bleeding over toolbars)
 * - Focal-point preserving Pinch-to-Zoom, Pan, Mouse-Wheel / Trackpad Zoom, Double-Tap Zoom, and +/- Zoom Controls
 * - Smooth Horizontal Swipe to switch pages at 1x (never misfires after pinching or panning)
 * - Precise speech bubble coordinate mapping [ymin, xmin, ymax, xmax] -> screen pixels
 * - Crisp unscaled Vietnamese translation overlay anchored dynamically to the zoomed bubble position,
 *   with free drag-to-move header, drag-to-resize corner handle, and quick A-/A+ controls
 */
@Composable
fun InteractiveComicImage(
    page: ComicPage,
    selectedBubbleIndex: Int?,
    onBubbleSelected: (Int?) -> Unit,
    showHints: Boolean = true,
    overlayScale: Float = 1.35f,
    fontSizeSp: Float = 12f,
    autoFitFont: Boolean = true,
    onIncreaseFontSize: () -> Unit = {},
    onDecreaseFontSize: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onPreviousPage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Live references so pointerInput blocks never restart mid-gesture
    val latestPage by rememberUpdatedState(page)
    val latestSelectedBubble by rememberUpdatedState(selectedBubbleIndex)
    val latestOnBubbleSelected by rememberUpdatedState(onBubbleSelected)
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)

    // Zoom & Pan state (automatically reset when page changes)
    var scale by remember(page.uri) { mutableFloatStateOf(MIN_ZOOM) }
    var panOffsetX by remember(page.uri) { mutableFloatStateOf(0f) }
    var panOffsetY by remember(page.uri) { mutableFloatStateOf(0f) }
    var zoomAnimJob by remember(page.uri) { mutableStateOf<Job?>(null) }

    // Drag-to-resize & drag-to-move offsets remembered per selected bubble
    var userExtraWidthPx by remember(page.uri, selectedBubbleIndex) { mutableFloatStateOf(0f) }
    var userExtraHeightPx by remember(page.uri, selectedBubbleIndex) { mutableFloatStateOf(0f) }
    var userOffsetXPx by remember(page.uri, selectedBubbleIndex) { mutableFloatStateOf(0f) }
    var userOffsetYPx by remember(page.uri, selectedBubbleIndex) { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val containerWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        val imgWidth = page.width.toFloat().coerceAtLeast(1f)
        val imgHeight = page.height.toFloat().coerceAtLeast(1f)

        val imageAspect = imgWidth / imgHeight
        val containerAspect = containerWidthPx / containerHeightPx

        // ContentScale.Fit geometry calculations
        val renderedWidthPx: Float
        val renderedHeightPx: Float
        val offsetXPx: Float
        val offsetYPx: Float

        if (containerAspect > imageAspect) {
            renderedHeightPx = containerHeightPx
            renderedWidthPx = containerHeightPx * imageAspect
            offsetXPx = (containerWidthPx - renderedWidthPx) / 2f
            offsetYPx = 0f
        } else {
            renderedWidthPx = containerWidthPx
            renderedHeightPx = containerWidthPx / imageAspect
            offsetXPx = 0f
            offsetYPx = (containerHeightPx - renderedHeightPx) / 2f
        }

        // Clamps pan strictly to scaled artwork bounds so zooming out or panning never reveals dead space
        fun clampPan(x: Float, y: Float, targetScale: Float): Pair<Float, Float> {
            if (targetScale <= 1.005f) return 0f to 0f
            val maxPanX = ((renderedWidthPx * targetScale - containerWidthPx) / 2f).coerceAtLeast(0f)
            val maxPanY = ((renderedHeightPx * targetScale - containerHeightPx) / 2f).coerceAtLeast(0f)
            return x.coerceIn(-maxPanX, maxPanX) to y.coerceIn(-maxPanY, maxPanY)
        }

        // Preserves the focal point under fingers / mouse cursor / double-tap point
        fun computeFocalPan(
            oldScale: Float,
            newScale: Float,
            oldPanX: Float,
            oldPanY: Float,
            focalPoint: Offset,
            panDelta: Offset = Offset.Zero
        ): Pair<Float, Float> {
            val centerX = containerWidthPx / 2f
            val centerY = containerHeightPx / 2f
            val ratio = newScale / oldScale.coerceAtLeast(0.01f)
            val rawX = (focalPoint.x - centerX) * (1f - ratio) + oldPanX * ratio + panDelta.x
            val rawY = (focalPoint.y - centerY) * (1f - ratio) + oldPanY * ratio + panDelta.y
            return clampPan(rawX, rawY, newScale)
        }

        // Re-clamp pan cleanly whenever container resizes (e.g. rotation / split-screen)
        LaunchedEffect(containerWidthPx, containerHeightPx, renderedWidthPx, renderedHeightPx) {
            val (clampedX, clampedY) = clampPan(panOffsetX, panOffsetY, scale)
            panOffsetX = clampedX
            panOffsetY = clampedY
        }

        // Smooth animated zoom transition for buttons and double-tap
        fun animateZoomTo(targetScaleRaw: Float, focalPoint: Offset) {
            val targetScale = targetScaleRaw.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val startScale = scale
            val startPanX = panOffsetX
            val startPanY = panOffsetY
            val (targetPanX, targetPanY) = if (targetScale <= 1.005f) {
                0f to 0f
            } else {
                computeFocalPan(startScale, targetScale, startPanX, startPanY, focalPoint)
            }

            zoomAnimJob?.cancel()
            zoomAnimJob = coroutineScope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                ) { fraction, _ ->
                    val currentScale = startScale + (targetScale - startScale) * fraction
                    val currentX = startPanX + (targetPanX - startPanX) * fraction
                    val currentY = startPanY + (targetPanY - startPanY) * fraction
                    scale = currentScale
                    val (cx, cy) = clampPan(currentX, currentY, currentScale)
                    panOffsetX = cx
                    panOffsetY = cy
                }
            }
        }

        // 1. Pinch-to-Zoom, Pan, and Horizontal Swipe (stable keys -> NEVER restarts mid-gesture!)
        val transformAndSwipeModifier = Modifier.pointerInput(
            page.uri,
            containerWidthPx,
            containerHeightPx,
            renderedWidthPx,
            renderedHeightPx
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.isConsumed) return@awaitEachGesture

                // Cancel any in-flight zoom animation so user touch takes over immediately
                zoomAnimJob?.cancel()

                var accumulatedZoom = 1f
                var accumulatedPan = Offset.Zero
                var pastTouchSlop = false
                val touchSlop = viewConfiguration.touchSlop
                var totalSwipeX = 0f
                var totalSwipeY = 0f
                var hadMultiTouch = false
                val startedZoomed = scale > 1.02f

                do {
                    val event = awaitPointerEvent()
                    val pressedChanges = event.changes.filter { it.pressed }
                    if (pressedChanges.size > 1) {
                        hadMultiTouch = true
                    }

                    val anyConsumed = event.changes.any { it.isConsumed }
                    if (!anyConsumed && event.changes.isNotEmpty()) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastTouchSlop) {
                            accumulatedZoom *= zoomChange
                            accumulatedPan += panChange

                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                            val panMotion = accumulatedPan.getDistance()

                            if (zoomMotion > touchSlop || panMotion > touchSlop || hadMultiTouch) {
                                pastTouchSlop = true
                            }
                        }

                        if (pastTouchSlop) {
                            if (hadMultiTouch || startedZoomed || scale > 1.02f) {
                                val oldScale = scale
                                val newScale = (oldScale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val focal = if (centroid != Offset.Unspecified) {
                                    centroid
                                } else {
                                    Offset(containerWidthPx / 2f, containerHeightPx / 2f)
                                }

                                val (newPanX, newPanY) = computeFocalPan(
                                    oldScale = oldScale,
                                    newScale = newScale,
                                    oldPanX = panOffsetX,
                                    oldPanY = panOffsetY,
                                    focalPoint = focal,
                                    panDelta = panChange
                                )

                                scale = if (newScale < 1.01f) MIN_ZOOM else newScale
                                panOffsetX = newPanX
                                panOffsetY = newPanY

                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) {
                                        change.consume()
                                    }
                                }
                            } else {
                                // At 1x single finger -> track horizontal swipe to flip pages
                                totalSwipeX += panChange.x
                                totalSwipeY += panChange.y
                                if (abs(totalSwipeX) > abs(totalSwipeY) * 1.15f) {
                                    event.changes.forEach { change ->
                                        if (change.position != change.previousPosition) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                // Flip page ONLY on intentional single-finger horizontal swipe while unzoomed
                if (!hadMultiTouch && !startedZoomed && scale <= 1.02f && pastTouchSlop) {
                    val swipeThresholdPx = 56f * density.density
                    if (abs(totalSwipeX) > abs(totalSwipeY) * 1.25f) {
                        if (totalSwipeX < -swipeThresholdPx) {
                            latestOnNextPage()
                        } else if (totalSwipeX > swipeThresholdPx) {
                            latestOnPreviousPage()
                        }
                    }
                }
            }
        }

        // 2. Mouse Wheel / Trackpad Scroll Zoom (desktop, ChromeOS, emulator)
        val mouseWheelModifier = Modifier.pointerInput(
            page.uri,
            containerWidthPx,
            containerHeightPx,
            renderedWidthPx,
            renderedHeightPx
        ) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        val change = event.changes.firstOrNull() ?: continue
                        val scrollY = change.scrollDelta.y
                        if (scrollY != 0f) {
                            zoomAnimJob?.cancel()
                            val oldScale = scale
                            val factor = if (scrollY < 0f) 1.15f else (1f / 1.15f)
                            val newScale = (oldScale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val (newPanX, newPanY) = computeFocalPan(
                                oldScale = oldScale,
                                newScale = newScale,
                                oldPanX = panOffsetX,
                                oldPanY = panOffsetY,
                                focalPoint = change.position
                            )
                            scale = if (newScale < 1.01f) MIN_ZOOM else newScale
                            panOffsetX = newPanX
                            panOffsetY = newPanY
                            change.consume()
                        }
                    }
                }
            }
        }

        // 3. Single-Tap (bubble selection) & Double-Tap (focal zoom toggle)
        val tapModifier = Modifier.pointerInput(
            page.uri,
            containerWidthPx,
            containerHeightPx,
            renderedWidthPx,
            renderedHeightPx
        ) {
            detectTapGestures(
                onDoubleTap = { tapOffset ->
                    if (scale > 1.05f) {
                        animateZoomTo(MIN_ZOOM, tapOffset)
                    } else {
                        animateZoomTo(DOUBLE_TAP_ZOOM, tapOffset)
                    }
                },
                onTap = { tapOffset ->
                    val centerX = containerWidthPx / 2f
                    val centerY = containerHeightPx / 2f
                    val tapX = centerX + (tapOffset.x - centerX - panOffsetX) / scale
                    val tapY = centerY + (tapOffset.y - centerY - panOffsetY) / scale

                    // Hit padding scales cleanly so bubbles remain easy to tap at any zoom
                    val hitTolerancePx = (16f * density.density) / scale
                    val bubbles = latestPage.bubbles

                    var hitIndex: Int? = null
                    for (i in bubbles.indices.reversed()) {
                        val bubble = bubbles[i]
                        val bLeft = offsetXPx + (bubble.xmin / 1000f) * renderedWidthPx
                        val bTop = offsetYPx + (bubble.ymin / 1000f) * renderedHeightPx
                        val bRight = offsetXPx + (bubble.xmax / 1000f) * renderedWidthPx
                        val bBottom = offsetYPx + (bubble.ymax / 1000f) * renderedHeightPx

                        if (tapX >= (bLeft - hitTolerancePx) && tapX <= (bRight + hitTolerancePx) &&
                            tapY >= (bTop - hitTolerancePx) && tapY <= (bBottom + hitTolerancePx)
                        ) {
                            hitIndex = i
                            break
                        }
                    }

                    if (hitIndex != null) {
                        if (latestSelectedBubble == hitIndex) {
                            latestOnBubbleSelected(null)
                        } else {
                            latestOnBubbleSelected(hitIndex)
                        }
                    } else {
                        latestOnBubbleSelected(null)
                    }
                }
            )
        }

        // Outer viewport box receiving canvas gestures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .then(mouseWheelModifier)
                .then(transformAndSwipeModifier)
                .then(tapModifier)
        ) {
            // Layer 1: Zoomable & Pannable comic image + speech bubble hint outlines
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = panOffsetX
                        translationY = panOffsetY
                    }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(page.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = page.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Subtle speech-bubble hint outlines anchored directly to the artwork
                if (showHints && page.bubbles.isNotEmpty()) {
                    page.bubbles.forEachIndexed { index, bubble ->
                        if (selectedBubbleIndex != index) {
                            val bLeftPx = offsetXPx + (bubble.xmin / 1000f) * renderedWidthPx
                            val bTopPx = offsetYPx + (bubble.ymin / 1000f) * renderedHeightPx
                            val bWidthPx = ((bubble.xmax - bubble.xmin) / 1000f) * renderedWidthPx
                            val bHeightPx = ((bubble.ymax - bubble.ymin) / 1000f) * renderedHeightPx

                            val leftDp = with(density) { bLeftPx.toDp() }
                            val topDp = with(density) { bTopPx.toDp() }
                            val widthDp = with(density) { bWidthPx.toDp() }
                            val heightDp = with(density) { bHeightPx.toDp() }

                            Box(
                                modifier = Modifier
                                    .offset(x = leftDp, y = topDp)
                                    .size(width = widthDp, height = heightDp)
                                    .border(
                                        width = (1.2f / scale).coerceAtLeast(0.5f).dp,
                                        color = Color(0x806366F1),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size((16f / scale.coerceAtMost(2f)).dp)
                                        .background(Color(0xB34F46E5), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = "Ô thoại đã dịch",
                                        tint = Color.White,
                                        modifier = Modifier.size((10f / scale.coerceAtMost(2f)).dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Layer 2: Crisp unscaled translation overlay anchored dynamically to the bubble's screen position
            if (selectedBubbleIndex != null && selectedBubbleIndex in page.bubbles.indices) {
                val bubble = page.bubbles[selectedBubbleIndex]

                val rawLeftPx = offsetXPx + (bubble.xmin / 1000f) * renderedWidthPx
                val rawTopPx = offsetYPx + (bubble.ymin / 1000f) * renderedHeightPx
                val rawWidthPx = ((bubble.xmax - bubble.xmin) / 1000f) * renderedWidthPx
                val rawHeightPx = ((bubble.ymax - bubble.ymin) / 1000f) * renderedHeightPx

                // Transform anchor into current screen-space viewport coordinates
                val centerX = containerWidthPx / 2f
                val centerY = containerHeightPx / 2f
                val anchorScreenLeftPx = centerX + (rawLeftPx - centerX) * scale + panOffsetX
                val anchorScreenTopPx = centerY + (rawTopPx - centerY) * scale + panOffsetY

                val minWidthPx = 196f * density.density
                val minHeightPx = 116f * density.density

                val scaledBaseWidthPx = (rawWidthPx * overlayScale).coerceAtLeast(minWidthPx)
                val scaledBaseHeightPx = (rawHeightPx * overlayScale).coerceAtLeast(minHeightPx)

                val maxAllowedWidthPx = (containerWidthPx - 16f).coerceAtLeast(minWidthPx)
                val maxAllowedHeightPx = (containerHeightPx - 16f).coerceAtLeast(minHeightPx)

                val actualWidthPx = (scaledBaseWidthPx + userExtraWidthPx)
                    .coerceIn(minWidthPx, maxAllowedWidthPx)
                val actualHeightPx = (scaledBaseHeightPx + userExtraHeightPx)
                    .coerceIn(minHeightPx, maxAllowedHeightPx)

                // Always keep overlay fully inside viewport bounds (zero clipping!)
                val clampedLeftPx = (anchorScreenLeftPx + userOffsetXPx)
                    .coerceIn(8f, (containerWidthPx - actualWidthPx - 8f).coerceAtLeast(8f))
                val clampedTopPx = (anchorScreenTopPx + userOffsetYPx)
                    .coerceIn(8f, (containerHeightPx - actualHeightPx - 8f).coerceAtLeast(8f))

                val widthDp = with(density) { actualWidthPx.toDp() }
                val heightDp = with(density) { actualHeightPx.toDp() }

                val textLength = bubble.translatedText.length
                val effectiveFontSizeSp = if (autoFitFont) {
                    val areaDp = widthDp.value * heightDp.value
                    when {
                        textLength > 160 -> (areaDp / 1650f).coerceIn(9.5f, 12f)
                        textLength > 100 -> (areaDp / 1250f).coerceIn(10.5f, 13f)
                        textLength > 50 -> (areaDp / 920f).coerceIn(11.5f, 14.5f)
                        else -> (areaDp / 680f).coerceIn(12.5f, 16f)
                    }
                } else {
                    fontSizeSp
                }
                val lineSpacingSp = (effectiveFontSizeSp * 1.38f).sp

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.94f, animationSpec = tween(160)),
                    exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(120)),
                    modifier = Modifier.offset {
                        IntOffset(clampedLeftPx.roundToInt(), clampedTopPx.roundToInt())
                    }
                ) {
                    Surface(
                        modifier = Modifier
                            .size(width = widthDp, height = heightDp)
                            .shadow(16.dp, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xF50B1120),
                        border = BorderStroke(1.5.dp, Color(0xCC818CF8))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                // Draggable Header (1:1 screen-pixel drag, double-tap to reset)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(page.uri, selectedBubbleIndex) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                userOffsetXPx += dragAmount.x
                                                userOffsetYPx += dragAmount.y
                                            }
                                        }
                                        .pointerInput(page.uri, selectedBubbleIndex) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    userOffsetXPx = 0f
                                                    userOffsetYPx = 0f
                                                    userExtraWidthPx = 0f
                                                    userExtraHeightPx = 0f
                                                }
                                            )
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Kéo để di chuyển khung",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF4F46E5), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "VIỆT HÓA",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.4.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    IconButton(
                                        onClick = {
                                            userOffsetXPx = 0f
                                            userOffsetYPx = 0f
                                            userExtraWidthPx = 0f
                                            userExtraHeightPx = 0f
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = "Đặt lại vị trí & kích thước",
                                            tint = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(0x2EFFFFFF))
                                            .clickable { onDecreaseFontSize() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "A-",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(0x2EFFFFFF))
                                            .clickable { onIncreaseFontSize() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "A+",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { onBubbleSelected(null) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Đóng",
                                            tint = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Translated dialogue body
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(bottom = 14.dp, start = 2.dp, end = 2.dp)
                                        .verticalScroll(rememberScrollState()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = bubble.translatedText,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = effectiveFontSizeSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = lineSpacingSp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // Bottom-Right Drag-to-Resize Handle (1:1 screen-pixel tracking)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(topStart = 12.dp, bottomEnd = 16.dp))
                                    .background(Color(0x666366F1))
                                    .pointerInput(page.uri, selectedBubbleIndex) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            userExtraWidthPx += dragAmount.x
                                            userExtraHeightPx += dragAmount.y
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInFull,
                                    contentDescription = "Kéo để phóng to khung dịch",
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Layer 3: Sleek Floating Zoom Controls (- | 100% | +) tucked at bottom-end
            val viewportCenter = Offset(containerWidthPx / 2f, containerHeightPx / 2f)
            val zoomPercent = (scale * 100f).roundToInt()
            val isZoomed = scale > 1.02f

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xCC0B1120),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isZoomed) Color(0x99818CF8) else Color(0x33FFFFFF)
                ),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    IconButton(
                        onClick = { animateZoomTo(scale - ZOOM_STEP, viewportCenter) },
                        enabled = scale > MIN_ZOOM + 0.01f,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("zoom_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOut,
                            contentDescription = "Thu nhỏ",
                            tint = if (scale > MIN_ZOOM + 0.01f) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = isZoomed) {
                                animateZoomTo(MIN_ZOOM, viewportCenter)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("zoom_reset_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$zoomPercent%",
                            color = if (isZoomed) Color(0xFFA5B4FC) else Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(
                        onClick = { animateZoomTo(scale + ZOOM_STEP, viewportCenter) },
                        enabled = scale < MAX_ZOOM - 0.01f,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("zoom_in_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = "Phóng to",
                            tint = if (scale < MAX_ZOOM - 0.01f) Color.White else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
