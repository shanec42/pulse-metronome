package com.austinlinux.dfmetronome.ui.history

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History card / Summary-from-History headline, e.g. "Tue, Oct 6 · 10:42 PM" (Claude Design). */
fun historyDateLabel(ts: Long): String = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(ts))

/** Date line under "Session complete", e.g. "Oct 7, 2026". */
fun shortDateLabel(ts: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts))
