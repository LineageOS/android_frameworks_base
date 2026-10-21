/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE
import android.window.WindowContext
import android.window.WindowProvider.KEY_REPARENT_TO_DEFAULT_DISPLAY_WITH_DISPLAY_REMOVAL
import com.android.app.tracing.TrackGroupUtils.trackGroup
import com.android.internal.util.EmergencyAffordanceManager
import com.android.internal.util.ScreenshotHelper
import com.android.systemui.CoreStartable
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.ConfigurationStateImpl
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractorImpl
import com.android.systemui.common.ui.view.ChoreographerUtils
import com.android.systemui.common.ui.view.ChoreographerUtilsImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayTypeRepository
import com.android.systemui.display.data.repository.ShadeDisplayTypeRepository
import com.android.systemui.display.domain.interactor.DisplayTypeInteractor
import com.android.systemui.keyevent.domain.interactor.SysUIKeyEventHandler
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.res.R
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.data.repository.MutableShadeDisplaysRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepositoryImpl
import com.android.systemui.shade.display.ShadeDisplayPolicyModule
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeDisplaysDialogInteractor
import com.android.systemui.shade.domain.interactor.ShadeDisplaysInteractor
import com.android.systemui.shade.domain.interactor.ShadeDisplaysInteractorImpl
import com.android.systemui.statusbar.notification.stack.NotificationStackRebindingHider
import com.android.systemui.statusbar.notification.stack.NotificationStackRebindingHiderImpl
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.phone.ConfigurationForwarder
import com.android.systemui.statusbar.phone.domain.interactor.ShadeDarkIconInteractor
import com.android.systemui.statusbar.phone.domain.interactor.ShadeDarkIconInteractorImpl
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.utils.windowmanager.WindowManagerProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Qualifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Module responsible for managing display-specific components and resources for the notification
 * shade window.
 *
 * This isolation is crucial because when the window transitions between displays, its associated
 * context, resources, and display characteristics (like density and size) also change. If the shade
 * window shared the same context as the rest of the system UI, it could lead to inconsistencies and
 * errors due to incorrect display information.
 *
 * By using this dedicated module, we ensure the notification shade window always utilizes the
 * correct display context and resources, regardless of the display it's on.
 */
@Module(includes = [ShadeDisplayPolicyModule::class])
object ShadeDisplayAwareModule {

    /** Creates a new context for the shade window. */
    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareContext(
        context: Context,
        @ShadeDisplayAware shadeContextBuildOptions: Bundle?,
    ): Context {
        return context
            .createWindowContext(context.display, TYPE_NOTIFICATION_SHADE, shadeContextBuildOptions)
            .apply { setTheme(R.style.Theme_SystemUI) }
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeContextBuildOptions(): Bundle? =
        // Enables to reparent this WindowContext to the default display if the currently
        // attached display is removed.
        Bundle().apply { putBoolean(KEY_REPARENT_TO_DEFAULT_DISPLAY_WITH_DISPLAY_REMOVAL, true) }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareWindowContext(@ShadeDisplayAware context: Context): WindowContext {
        // We rely on the fact context is a WindowContext as the API to reparent windows is only
        // available there.
        return (context as? WindowContext)
            ?: error(
                "ShadeDisplayAware context must be a window context to allow window reparenting."
            )
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowLayoutParams(@ShadeDisplayAware context: Context): LayoutParams {
        return ShadeWindowLayoutParams.create(context)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowManager(
        @ShadeDisplayAware context: Context,
        windowManagerProvider: WindowManagerProvider,
    ): WindowManager {
        return windowManagerProvider.getWindowManager(context)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareResources(@ShadeDisplayAware context: Context): Resources {
        return context.resources
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun providesDisplayAwareLayoutInflater(@ShadeDisplayAware context: Context): LayoutInflater {
        return LayoutInflater.from(context)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowConfigurationController(
        @ShadeDisplayAware shadeContext: Context,
        factory: ConfigurationControllerImpl.Factory,
    ): ConfigurationController {
        return factory.create(shadeContext)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideConfigurationForwarder(
        @ShadeDisplayAware shadeConfigurationController: ConfigurationController
    ): ConfigurationForwarder {
        return shadeConfigurationController
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeDisplayAwareConfigurationState(
        factory: ConfigurationStateImpl.Factory,
        @ShadeDisplayAware configurationController: ConfigurationController,
        @ShadeDisplayAware context: Context,
    ): ConfigurationState {

        return factory.create(context, configurationController)
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun shadeDisplayAwareSystemBarUtilsState(
        @ShadeDisplayAware context: Context,
        @ShadeDisplayAware configurationController: ConfigurationController,
        factory: SystemBarUtilsState.Factory,
    ): SystemBarUtilsState {
        return factory.create(context, configurationController)
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeDisplayAwareConfigurationRepository(
        factory: ConfigurationRepositoryImpl.Factory,
        @ShadeDisplayAware configurationController: ConfigurationController,
        @ShadeDisplayAware context: Context,
    ): ConfigurationRepository {
        return factory.create(context, configurationController)
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeAwareConfigurationInteractor(
        @ShadeDisplayAware configurationRepository: ConfigurationRepository
    ): ConfigurationInteractor {
        return ConfigurationInteractorImpl(configurationRepository)
    }

    @SysUISingleton
    @Provides
    fun provideShadePositionRepository(
        impl: MutableShadeDisplaysRepository
    ): ShadeDisplaysRepository {
        return impl
    }

    @SysUISingleton
    @Provides
    fun provideMutableShadePositionRepository(
        impl: ShadeDisplaysRepositoryImpl
    ): MutableShadeDisplaysRepository {
        return impl
    }

    /** Provides the ID of the display currently hosting the shade window. */
    @Provides
    @ShadeDisplayAware
    fun provideShadeDisplayId(repository: ShadeDisplaysRepository): StateFlow<Int> =
        repository.displayId

    @Provides
    @SysUISingleton
    fun provideShadeDialogContextInteractor(
        impl: ShadeDialogContextInteractorImpl
    ): ShadeDialogContextInteractor = impl

    @Provides
    @IntoMap
    @ClassKey(ShadeDialogContextInteractor::class)
    fun provideShadeDialogContextInteractorCoreStartable(
        impl: ShadeDialogContextInteractorImpl
    ): CoreStartable = impl

    @Provides
    @IntoMap
    @ClassKey(ShadePrimaryDisplayCommand::class)
    fun provideShadePrimaryDisplayCommand(impl: ShadePrimaryDisplayCommand): CoreStartable = impl

    /**
     * Provided for making classes easier to test. In tests, a custom method to wait for the next
     * frame can be easily provided.
     */
    @Provides fun provideChoreographerUtils(): ChoreographerUtils = ChoreographerUtilsImpl

    @Provides
    @ShadeOnDefaultDisplayWhenLocked
    fun provideShadeOnDefaultDisplayWhenLocked(): Boolean = true

    /** Provides a [LogBuffer] for use by classes related to shade movement */
    @Provides
    @SysUISingleton
    @ShadeDisplayLog
    fun provideShadeDisplayLogLogBuffer(factory: LogBufferFactory): LogBuffer {
        val logBufferName = "ShadeDisplayLog"
        return factory.create(
            logBufferName,
            maxSize = 400,
            alwaysLogToLogcat = true,
            systraceTrackName = trackGroup("shade", logBufferName),
        )
    }

    @Provides
    @IntoMap
    @ClassKey(ShadeDisplaysDialogInteractor::class)
    fun provideShadeDisplayDialogInteractor(impl: ShadeDisplaysDialogInteractor): CoreStartable =
        impl

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayTypeRepository(
        shadeDisplayTypeRepository: ShadeDisplayTypeRepository
    ): DisplayTypeRepository {
        return shadeDisplayTypeRepository
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideDisplayTypeInteractor(
        @ShadeDisplayAware displayTypeRepository: DisplayTypeRepository
    ): DisplayTypeInteractor {
        return DisplayTypeInteractor(displayTypeRepository)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun emergencyAffordanceManager(
        @ShadeDisplayAware shadeContext: Context
    ): EmergencyAffordanceManager {
        return EmergencyAffordanceManager(shadeContext)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideScreenshotHelper(@ShadeDisplayAware shadeContext: Context): ScreenshotHelper {
        return ScreenshotHelper(shadeContext)
    }
}

/**
 * Module that should be included only if the shade window [WindowRootView] is available.
 *
 * This includes SystemUIGoogle variant.
 */
@Module
object ShadeDisplayAwareWithShadeWindowModule {

    @Provides
    @SysUISingleton
    fun bindShadeDisplaysInteractor(impl: ShadeDisplaysInteractorImpl): ShadeDisplaysInteractor =
        impl

    @Provides
    @IntoMap
    @ClassKey(ShadeDisplaysInteractorImpl::class)
    fun provideShadeDisplaysInteractorCoreStartable(
        impl: ShadeDisplaysInteractorImpl
    ): CoreStartable = impl

    @Provides
    @SysUISingleton
    fun bindNotificationStackRebindingHider(
        impl: NotificationStackRebindingHiderImpl
    ): NotificationStackRebindingHider = impl

    @Provides
    @SysUISingleton
    fun bindShadeDarkIconInteractor(impl: ShadeDarkIconInteractorImpl): ShadeDarkIconInteractor =
        impl
}

/**
 * Dagger module to be included in Android variants where the `WindowRootView` (responsible for the
 * movable shade window) is NOT present, such as Wear OS or Android TV.
 *
 * Since `SystemUIModule` is common to all variants, some of its bound classes may have dependencies
 * expecting the shade window. This module ensures these dependencies are satisfied with no-op
 * implementations when the shade window is unavailable.
 *
 * Ideally, classes having WindowRootView dependencies shouldn't be instantiated at all in variants
 * that don't provide it, but sometimes this is not possible or too complicated.
 *
 * Making a concrete example might help understanding this: the Wear of sysui seems to be including
 * [SceneContainerFrameworkModule] that has some classes depending to the shade window and its
 * position. While it's unclear why Wear needs to depend on the Scene container, providing here a
 * no-op [ShadeDisplaysInteractor] will guarantee no classes depending on the WindowRootView are
 * created (as the window root view is not available).
 */
@Module
object ShadeDisplayAwareWindowWithoutShadeModule {

    @Provides
    @SysUISingleton
    fun bindShadeDisplaysInteractor(): ShadeDisplaysInteractor =
        object : ShadeDisplaysInteractor {
            override val displayId: StateFlow<Int> = MutableStateFlow(Display.DEFAULT_DISPLAY)
            override val pendingDisplayId: StateFlow<Int> =
                MutableStateFlow(Display.DEFAULT_DISPLAY)
        }

    /**
     * [QuickSettingsController] is needed by [SysUIKeyEventHandler] dependencies.
     * [SysUIKeyEventHandler] is used from [ConnectedDisplayConstraintLayoutKeyguardPresentation],
     * that seems to be injected also in the Wear sysui variant. Ideally Wear code should be
     * restructured to remove this dep from their dagger graph, but in the meantime this allows the
     * target to compile.
     */
    @Provides
    @SysUISingleton
    fun providesQuickSettingsControllerNoOp(): QuickSettingsController =
        NoOpQuickSettingsController()
}

/**
 * Annotates the boolean value that defines whether the shade window should go back to the default
 * display when the keyguard is visible.
 *
 * As of today (Dec 2024), This is a configuration parameter provided in the dagger graph as the
 * final policy around keyguard display is still under discussion, and will be evaluated based on
 * how well this solution behaves from the performance point of view.
 */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ShadeOnDefaultDisplayWhenLocked

/** A [com.android.systemui.log.LogBuffer] for changes to the shade display. */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ShadeDisplayLog
