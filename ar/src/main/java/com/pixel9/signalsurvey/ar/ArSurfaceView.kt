package com.pixel9.signalsurvey.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A GLSurfaceView that renders the ARCore camera feed and hands each [Frame] to a listener.
 *
 * Being a plain SurfaceView is what makes [SnapshotCapturer] simple: PixelCopy can read the
 * rendered surface directly, so a "photo" is the exact camera image the operator saw, with
 * none of our own overlay burned into it.
 */
class ArSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    fun interface FrameListener {
        /** Called on the GL thread, once per rendered frame. Keep the work bounded. */
        fun onFrame(session: Session, frame: Frame, viewWidth: Int, viewHeight: Int)
    }

    private val background = BackgroundRenderer()
    private var session: Session? = null
    private var frameListener: FrameListener? = null
    private var textureBound = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var displayRotation = 0
    private var geometryDirty = true

    /**
     * Gate on the GL thread. Volatile because it is flipped from the main thread while the GL
     * thread may be mid-frame — the flag closes the window, and the catch below covers the
     * frame already in flight when it closes.
     */
    @Volatile private var renderingEnabled = false

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(Renderer())
        renderMode = RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    fun attach(session: Session, listener: FrameListener) {
        this.session = session
        this.frameListener = listener
        this.textureBound = false
        this.geometryDirty = true
    }

    fun detach() {
        renderingEnabled = false
        session = null
        frameListener = null
    }

    /**
     * Start drawing. Call only *after* the ARCore session has been resumed.
     *
     * Order matters and is not symmetric: ARCore requires the session to be live before the GL
     * surface starts drawing, and the surface to stop before the session is paused. Getting it
     * backwards means the GL thread calls update() on a paused session, which throws
     * SessionPausedException and — on a background thread — takes the process down.
     */
    fun startRendering() {
        renderingEnabled = true
        onResume()
    }

    /** Stop drawing. Call *before* pausing the ARCore session. */
    fun stopRendering() {
        renderingEnabled = false
        onPause()
    }

    /** Call from onResume and on configuration change. */
    fun updateDisplayGeometry(display: Display) {
        displayRotation = display.rotation
        geometryDirty = true
    }

    private inner class Renderer : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            background.createOnGlThread()
            textureBound = false
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceW = width
            surfaceH = height
            geometryDirty = true
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            // The session may have been paused between this frame being scheduled and running.
            if (!renderingEnabled) return

            if (!textureBound) {
                s.setCameraTextureName(background.textureId)
                textureBound = true
            }
            if (geometryDirty && surfaceW > 0 && surfaceH > 0) {
                s.setDisplayGeometry(displayRotation, surfaceW, surfaceH)
                geometryDirty = false
            }

            val frame = try {
                s.update()
            } catch (e: CameraNotAvailableException) {
                Log.w(TAG, "Camera unavailable during update", e)
                return
            } catch (e: SessionPausedException) {
                // Lost the race: the session was paused after this frame was scheduled. Normal
                // during a lifecycle transition, and skipping the frame is the whole response.
                return
            } catch (e: Throwable) {
                // This runs on the GL thread, where an escaping exception is a process death
                // rather than a caught error. No single frame is worth that.
                Log.e(TAG, "Session update failed; skipping frame", e)
                return
            }

            background.draw(frame)

            try {
                frameListener?.onFrame(s, frame, surfaceW, surfaceH)
            } catch (t: Throwable) {
                // A listener crash must not take down the GL thread and the session with it.
                Log.e(TAG, "Frame listener threw", t)
            }
        }
    }

    private companion object { const val TAG = "ArSurfaceView" }
}
