package ru.poneslas.vahter.probe.push

import ru.poneslas.vahter.probe.entry.EntryTrampoline

/** 12а (via=service) и 12в (via=showAssist): уведомление → прозрачная активность → лист. */
class NotifyTrampolineActivity : EntryTrampoline("notify")
