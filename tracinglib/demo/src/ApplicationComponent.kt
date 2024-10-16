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
package com.example.tracing.demo

import com.example.tracing.demo.experiments.CancellableSharedFlow
import com.example.tracing.demo.experiments.CollectFlow
import com.example.tracing.demo.experiments.CombineDeferred
import com.example.tracing.demo.experiments.Experiment
import com.example.tracing.demo.experiments.LaunchNested
import com.example.tracing.demo.experiments.LaunchSequentially
import com.example.tracing.demo.experiments.LeakySharedFlow
import com.example.tracing.demo.experiments.SharedFlowUsage
import com.example.tracing.demo.experiments.startThreadWithLooper
import dagger.Binds
import dagger.Component
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class Main

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class Default

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class IO

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class Unconfined

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class FixedThreadA

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class FixedThreadB

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class FixedThreadC

@Qualifier @MustBeDocumented @Retention(RUNTIME) annotation class FixedThreadD

@Module
class ConcurrencyModule {

    @Provides
    @Singleton
    @Default
    fun provideDefaultDispatcher(): CoroutineDispatcher {
        return Dispatchers.Default
    }

    @Provides
    @Singleton
    @IO
    fun provideIODispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }

    @Provides
    @Singleton
    @Unconfined
    fun provideUnconfinedDispatcher(): CoroutineDispatcher {
        return Dispatchers.Unconfined
    }

    @Provides
    @Singleton
    @FixedThreadA
    fun provideDispatcherA(): CoroutineDispatcher {
        return startThreadWithLooper("Thread:A").threadHandler.asCoroutineDispatcher()
    }

    @Provides
    @Singleton
    @FixedThreadB
    fun provideDispatcherB(): CoroutineDispatcher {
        return startThreadWithLooper("Thread:B").threadHandler.asCoroutineDispatcher()
    }

    @Provides
    @Singleton
    @FixedThreadC
    fun provideDispatcherC(): CoroutineDispatcher {
        return startThreadWithLooper("Thread:C").threadHandler.asCoroutineDispatcher()
    }

    @Provides
    @Singleton
    @FixedThreadD
    fun provideDispatcherD(): CoroutineDispatcher {
        return startThreadWithLooper("Thread:D").threadHandler.asCoroutineDispatcher()
    }
}

@Module
interface ExperimentModule {
    @Binds
    @IntoMap
    @ClassKey(CollectFlow::class)
    fun bindCollectFlow(service: CollectFlow): Experiment

    @Binds
    @IntoMap
    @ClassKey(SharedFlowUsage::class)
    fun bindSharedFlowUsage(service: SharedFlowUsage): Experiment

    @Binds
    @IntoMap
    @ClassKey(LeakySharedFlow::class)
    fun bindLeakySharedFlow(service: LeakySharedFlow): Experiment

    @Binds
    @IntoMap
    @ClassKey(CancellableSharedFlow::class)
    fun bindCancellableSharedFlow(service: CancellableSharedFlow): Experiment

    @Binds
    @IntoMap
    @ClassKey(CombineDeferred::class)
    fun bindCombineDeferred(service: CombineDeferred): Experiment

    @Binds
    @IntoMap
    @ClassKey(LaunchNested::class)
    fun bindLaunchNested(service: LaunchNested): Experiment

    @Binds
    @IntoMap
    @ClassKey(LaunchSequentially::class)
    fun bindLaunchSequentially(service: LaunchSequentially): Experiment
}

@Singleton
@Component(modules = [ConcurrencyModule::class, ExperimentModule::class])
interface ApplicationComponent {
    /** Returns [Experiment]s that should be used with the application. */
    @Singleton fun getAllExperiments(): Map<Class<*>, Provider<Experiment>>
}
