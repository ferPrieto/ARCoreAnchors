package fernando.prieto.arcore

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import androidx.annotation.GuardedBy
import androidx.fragment.app.Fragment
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.CloudAnchorMode
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.common.base.Preconditions
import com.google.firebase.database.DatabaseError
import fernando.prieto.ar_core.helpers.CameraPermissionHelper
import fernando.prieto.ar_core.helpers.DisplayRotationHelper
import fernando.prieto.ar_core.helpers.SnackbarHelper
import fernando.prieto.ar_core.helpers.TrackingStateHelper
import fernando.prieto.ar_core.managers.CloudAnchorManager
import fernando.prieto.ar_core.managers.FirebaseManager
import fernando.prieto.ar_core.rendering.BackgroundRenderer
import fernando.prieto.ar_core.rendering.ObjectRenderer
import fernando.prieto.ar_core.rendering.PlaneRenderer
import fernando.prieto.ar_core.rendering.PointCloudRenderer
import kotlinx.android.synthetic.main.fragment_camera.*
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "FirstFragment"
private val OBJECT_COLOR = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)

class CameraFragment : Fragment(), GLSurfaceView.Renderer,
    CloudAnchorManager.CloudAnchorHostListener,
    FirebaseManager.RoomCodeListener,
    CloudAnchorManager.CloudAnchorResolveListener {

    private val singleTapLock = Object()
    private val anchorLock = Object()

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private val backgroundRenderer =
        BackgroundRenderer()
    private val virtualObject: ObjectRenderer =
        ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer =
        ObjectRenderer()
    private val planeRenderer: PlaneRenderer =
        PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer =
        PointCloudRenderer()

    // Helpers
    private lateinit var trackingStateHelper: TrackingStateHelper
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private lateinit var snackbarHelper: SnackbarHelper

    // Mangers
    private lateinit var cloudManager: CloudAnchorManager
    private lateinit var firebaseManager: FirebaseManager

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private lateinit var gestureDetector: GestureDetector

    private var session: Session? = null
    private var roomCode: Long? = null
    private var cloudAnchorId: String? = null
    private var installRequested = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        initialiseHelpers()
        initialiseManagers()

        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    private fun initialiseManagers() {
        firebaseManager = FirebaseManager(context)
        cloudManager = CloudAnchorManager()
    }

    private fun initialiseHelpers() {
        trackingStateHelper = TrackingStateHelper(activity)
        snackbarHelper = SnackbarHelper()
        displayRotationHelper = DisplayRotationHelper(context)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initialiseTextView()
        setupGestureDetector()
        setupGLSurface()
        installRequested = false
        firebaseManager.registerNewListenerForLastRoomAdded(this)
    }

    private fun initialiseTextView() {
        ArCoreApk.getInstance().checkAvailability(context).let { availability ->
            if (availability.isTransient) {
                Handler().postDelayed({ initialiseTextView() }, 200)
            }
            arSupported.text = if (availability.isSupported) {
                getString(R.string.camera_text_ar_supported)
            } else {
                getString(R.string.camera_text_ar_not_supported)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        createSession()
        surfaceView.onResume()
        displayRotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        pauseSessionInvolvedElements()
    }

    private fun pauseSessionInvolvedElements() {
        session?.let { session ->
            displayRotationHelper.onPause()
            surfaceView.onPause()
            session.pause()
        }
    }

    private fun createSession() {
        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {
                    }
                }
                if (!CameraPermissionHelper.hasCameraPermission(
                        activity
                    )
                ) {
                    CameraPermissionHelper.requestCameraPermission(
                        activity
                    )
                    return
                }
                session = Session(context)
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.snackbar_arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.snackbar_arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.snackbar_arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.snackbar_arcore_exception
                exception = e
            }
            exception?.let {
                snackbarHelper.showError(activity, getString(messageId))
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            Config(session).apply {
                cloudAnchorMode = CloudAnchorMode.ENABLED
                session?.configure(this)
            }
            cloudManager.setSession(session)
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            snackbarHelper.showError(activity, getString(R.string.snackbar_camera_unavailable))
            session = null
            return
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(
            context,
            object : SimpleOnGestureListener() {
                override fun onSingleTapUp(motionEvent: MotionEvent): Boolean {
                    synchronized(singleTapLock) {
                        queuedSingleTap = motionEvent
                    }
                    return true
                }

                override fun onDown(e: MotionEvent) = true
            })
    }

    private fun setupGLSurface() {
        surfaceView.setOnTouchListener { _: View?, event: MotionEvent? ->
            gestureDetector.onTouchEvent(event)
        }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)
    }

    private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean =
        hit.trackable.let { trackable ->
            when (trackable) {
                is Plane -> {
                    trackable.isPoseInPolygon(hit.hitPose)
                }
                is Point -> {
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                }
                else -> false
            }
        }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        session?.let { session ->
            displayRotationHelper.updateSessionIfNeeded(session)

            try {
                session.setCameraTextureName(backgroundRenderer.textureId)

                val frame = session.update()
                val camera = frame.camera
                val cameraTrackingState = camera.trackingState

                cloudManager.onUpdate()

                handleTap(frame, cameraTrackingState)

                backgroundRenderer.draw(frame)

                trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

                if (cameraTrackingState == TrackingState.PAUSED) {
                    return
                }


                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                frame.acquirePointCloud().use { pointCloud ->
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                }

                planeRenderer.drawPlanes(
                    session.getAllTrackables(Plane::class.java),
                    camera.displayOrientedPose,
                    projectionMatrix
                )

                var shouldDrawAnchor = false
                synchronized(anchorLock) {
                    anchor?.let {
                        if (it.trackingState == TrackingState.TRACKING) {
                            it.pose?.toMatrix(anchorMatrix, 0)
                            shouldDrawAnchor = true
                        }
                    }
                }

                if (shouldDrawAnchor) {
                    val colorCorrectionRgba = FloatArray(4)
                    frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

                    virtualObject.updateModelMatrix(anchorMatrix, 1.0f)
                    virtualObjectShadow.updateModelMatrix(anchorMatrix, 1.0f)
                    virtualObject.draw(
                        viewMatrix,
                        projectionMatrix,
                        colorCorrectionRgba,
                        OBJECT_COLOR
                    )
                    virtualObjectShadow.draw(
                        viewMatrix,
                        projectionMatrix,
                        colorCorrectionRgba,
                        OBJECT_COLOR
                    )
                } else return
            } catch (t: Throwable) {
                Log.e(TAG, "Exception on the OpenGL thread", t)
            }
        }
    }

    private fun handleTap(
        frame: Frame,
        cameraTrackingState: TrackingState
    ) {
        synchronized(singleTapLock) {
            synchronized(anchorLock) {
                queuedSingleTap?.let {
                    if (anchor == null && cameraTrackingState == TrackingState.TRACKING) {
                        frame.hitTest(queuedSingleTap).map { hit ->
                            if (shouldCreateAnchorWithHit(hit)) {
                                val newAnchor = hit.createAnchor()
                                Preconditions.checkNotNull(
                                    this,
                                    "The host listener cannot be null."
                                )
                                cloudManager.hostCloudAnchor(newAnchor, this)
                                setNewAnchor(newAnchor)
                                snackbarHelper.showMessage(
                                    activity,
                                    getString(R.string.snackbar_anchor_placed)
                                )
                                return@map
                            }
                        }
                    }
                }
            }
            queuedSingleTap = null
        }
    }


    override fun onSurfaceChanged(p0: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(context)
            planeRenderer.createOnGlThread(context, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(context)
            virtualObject.createOnGlThread(context, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(
                context, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to read an asset file", ex)
        }
    }

    override fun onNewRoomCode(newRoomCode: Long?) {
        roomCode = newRoomCode
        checkAndMaybeShare()
        onRoomCodeEntered(newRoomCode)
    }

    override fun onError(error: DatabaseError) {
        Log.w(TAG, "A Firebase database error happened.", error.toException())
        snackbarHelper.showError(
            activity, getString(R.string.snackbar_firebase_error)
        )
    }

    override fun onCloudTaskComplete(anchor: Anchor) {
        val cloudState = anchor.cloudAnchorState
        if (cloudState.isError) {
            Log.e(TAG, "Error hosting a cloud anchor, state $cloudState")
            snackbarHelper.showMessageWithDismiss(
                activity, getString(R.string.snackbar_host_error, cloudState)
            )
            return
        }
        /*Preconditions.checkState(
            cloudAnchorId == null, "The cloud anchor ID cannot have been set before."
        )*/
        cloudAnchorId = anchor.cloudAnchorId
        setNewAnchor(anchor)
        checkAndMaybeShare()
    }

    private fun checkAndMaybeShare() {
        if (roomCode == null || cloudAnchorId == null) {
            return
        }
        firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchorId)
        snackbarHelper.showMessageWithDismiss(
            activity,
            getString(R.string.snackbar_cloud_id_shared)
        )
    }

    /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.  */
    private fun setNewAnchor(newAnchor: Anchor?) {
        synchronized(anchorLock) {
            anchor?.detach()
            anchor = newAnchor
        }
    }

    override fun onCloudAnchorResolveTaskComplete(anchor: Anchor) {
        val cloudState = anchor.cloudAnchorState
        if (cloudState.isError) {
            Log.w(
                TAG,
                "The anchor in room "
                        + roomCode
                        + " could not be resolved. The error state was "
                        + cloudState
            )
            snackbarHelper.showMessageWithDismiss(
                activity,
                getString(R.string.snackbar_resolve_error, cloudState)
            )
            return
        }
        snackbarHelper.showMessageWithDismiss(
            activity,
            getString(R.string.snackbar_resolve_success)
        )
        setNewAnchor(anchor)
    }

    override fun onShowResolveMessage() {
        snackbarHelper.setMaxLines(4)
        snackbarHelper.showMessageWithDismiss(
            activity, getString(R.string.snackbar_resolve_no_result_yet)
        )
    }

    /** Resets the mode of the app to its initial state and removes the anchors.  */
    private fun resetMode() {
        firebaseManager.clearRoomListener()
        setNewAnchor(null)
        snackbarHelper.hide(activity)
        cloudManager.clearListeners()
    }

    /** Callback function invoked when the user presses the OK button in the Resolve Dialog.  */
    private fun onRoomCodeEntered(roomCode: Long?) {
        firebaseManager.registerNewListenerForRoom(
            roomCode
        ) { cloudAnchorId ->
            cloudManager.resolveCloudAnchor(
                cloudAnchorId, this, SystemClock.uptimeMillis()
            )
        }
    }
}
