package ru.poneslas.vahter.ui

import ru.poneslas.vahter.R

/** Иконки прототипа (docs/design/prototype/core.js, объект I) — векторные ресурсы res/drawable/ic_*.xml. */
object Ic {
    val arrow = R.drawable.ic_arrow
    val back = R.drawable.ic_back
    val bell = R.drawable.ic_bell
    val book = R.drawable.ic_book
    val btn = R.drawable.ic_btn
    val cam = R.drawable.ic_cam
    val cart = R.drawable.ic_cart
    val chat = R.drawable.ic_chat
    val chev = R.drawable.ic_chev
    val circle = R.drawable.ic_circle
    val clip = R.drawable.ic_clip
    val crop = R.drawable.ic_crop
    val del = R.drawable.ic_del
    val dl = R.drawable.ic_dl
    val doc = R.drawable.ic_doc
    val down = R.drawable.ic_down
    val edit = R.drawable.ic_edit
    val expand = R.drawable.ic_expand
    val flight = R.drawable.ic_flight
    val food = R.drawable.ic_food
    val gear = R.drawable.ic_gear
    val hist = R.drawable.ic_hist
    val image = R.drawable.ic_image
    val info = R.drawable.ic_info
    val kbd = R.drawable.ic_kbd
    val lens = R.drawable.ic_lens
    val list = R.drawable.ic_list
    val lock = R.drawable.ic_lock
    val mic = R.drawable.ic_mic
    val paste = R.drawable.ic_paste
    val pen = R.drawable.ic_pen
    val plus = R.drawable.ic_plus
    val more = R.drawable.ic_more
    val power = R.drawable.ic_power
    val qr = R.drawable.ic_qr
    val send = R.drawable.ic_send
    val shield = R.drawable.ic_shield
    val shot = R.drawable.ic_shot
    val spark = R.drawable.ic_spark
    val srv = R.drawable.ic_srv
    val star = R.drawable.ic_star
    val transl = R.drawable.ic_transl
    val undo = R.drawable.ic_undo
    val warn = R.drawable.ic_warn
    val wave = R.drawable.ic_wave
    val x = R.drawable.ic_x

    /** Значок режима по имени из модели (spark, transl, lens, food…). */
    fun mode(name: String): Int = when (name) {
        "spark" -> spark
        "transl" -> transl
        "lens" -> lens
        "food" -> food
        "cart" -> cart
        "flight" -> flight
        "book" -> book
        "star" -> star
        "edit" -> edit
        else -> star
    }
}
