package com.duragim.fakelocation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Servis ile ekran arasında paylaşılan basit durum. */
object MockState {
    sealed interface Status {
        data object Idle : Status
        data class Running(val lat: Double, val lng: Double) : Status
        /** Uygulama Geliştirici seçeneklerinde "sahte konum uygulaması" olarak seçilmemiş. */
        data object NotMockApp : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    internal fun set(s: Status) { _status.value = s }
}
