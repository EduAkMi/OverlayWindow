package com.overlaywindow.overlay.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hospeda um [ComposeView] diretamente no [WindowManager], fora de qualquer Activity.
 *
 * Numa Activity o Compose recebe "de graça" uma série de owners pela árvore de Views.
 * Aqui esta classe faz esse papel:
 * - [LifecycleOwner] e [SavedStateRegistryOwner]: obrigatórios para qualquer ComposeView;
 * - [ViewModelStoreOwner]: permite `viewModel()` e é exigido pelo `NavHost`;
 * - [NavigationEventDispatcherOwner]: exigido pelo `NavHost` (Navigation 2.10+) para tratar
 *   o "voltar". Numa Activity quem fornece é a ComponentActivity.
 *
 * O botão voltar só chega a janelas *focáveis* (ver [setFocusable]). Ele é repassado ao
 * [NavigationEventDispatcher]; quando nenhum handler habilitado o consome (ex.: NavHost já
 * na tela inicial), o fallback chama [onBack].
 */
class ComposeOverlayWindow(
    context: Context,
    windowType: Int,
    gravity: Int = Gravity.TOP or Gravity.START,
    width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    initialX: Int = 0,
    initialY: Int = 0,
    focusable: Boolean = false,
    private val onBack: () -> Unit = {},
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, NavigationEventDispatcherOwner {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val navigationEventDispatcher = NavigationEventDispatcher(
        object : OnBackCompletedFallback {
            override fun onBackCompletedFallback() = onBack()
        },
    )

    /** Entrada "manual" de eventos: o voltar do sistema chega por [BackInterceptingLayout]. */
    private val backInput = DirectNavigationEventInput().also { navigationEventDispatcher.addInput(it) }

    private val params = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        flagsFor(focusable),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        x = initialX
        y = initialY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private val root = BackInterceptingLayout(context) { backInput.backCompleted() }.apply {
        setViewTreeLifecycleOwner(this@ComposeOverlayWindow)
        setViewTreeSavedStateRegistryOwner(this@ComposeOverlayWindow)
        setViewTreeViewModelStoreOwner(this@ComposeOverlayWindow)
        setViewTreeNavigationEventDispatcherOwner(this@ComposeOverlayWindow)
        addView(ComposeView(context).apply { setContent(content) })
    }

    var isShowing: Boolean = false
        private set

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    val position: Pair<Int, Int> get() = params.x to params.y

    fun show() {
        if (isShowing || lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        windowManager.addView(root, params)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        isShowing = true
    }

    /**
     * Tira a janela da tela sem destruí-la: o ViewModelStore (e o que vive nele) é mantido,
     * e [show] pode recolocá-la depois. A composição é descartada e refeita ao reexibir.
     */
    fun hide() {
        if (!isShowing) return
        windowManager.removeView(root)
        isShowing = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun moveTo(x: Int, y: Int) = update {
        this.x = x
        this.y = y
    }

    fun setWidth(width: Int) = update { this.width = width }

    /**
     * Janela focável recebe teclado (IME) e o botão voltar, mas os tira do app de baixo.
     * Por isso o ideal é ligar o foco só quando a interação exigir (ex.: painel expandido).
     */
    fun setFocusable(focusable: Boolean) = update { flags = flagsFor(focusable) }

    private fun update(block: WindowManager.LayoutParams.() -> Unit) {
        params.block()
        if (isShowing) windowManager.updateViewLayout(root, params)
    }

    /** Remove a janela; a instância não pode ser reutilizada depois disso. */
    fun destroy() {
        if (isShowing) {
            windowManager.removeView(root)
            isShowing = false
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        navigationEventDispatcher.dispose()
    }

    private companion object {
        fun flagsFor(focusable: Boolean): Int {
            // NOT_TOUCH_MODAL: toques fora da janela continuam indo para o app de baixo.
            val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            return if (focusable) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }
}

/**
 * Raiz da janela que converte o "voltar" do sistema em [onBack].
 * - Android 13+: registra um [OnBackInvokedCallback] no dispatcher da própria janela —
 *   com targetSdk recente o KEYCODE_BACK deixa de ser entregue (predictive back).
 * - Versões anteriores: intercepta o KEYCODE_BACK.
 */
@SuppressLint("ViewConstructor")
private class BackInterceptingLayout(
    context: Context,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    private var backCallback: Any? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        val dispatcher = findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        val callback = backCallback as? OnBackInvokedCallback ?: return
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        backCallback = null
    }
}
