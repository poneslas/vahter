package ru.poneslas.vahter.ui

/** Число с одним знаком после запятой, по-русски: 1,5 (МБ, секунды). */
fun comma1(x: Double): String = "%.1f".format(x).replace('.', ',')
