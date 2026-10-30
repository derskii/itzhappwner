package com.happwner

import android.app.Activity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

/** Shared picker/editor: opening a preset never mutates the active request. */
object ProfileDialogs {
    private fun t(a: Activity, ru: String, en: String): String =
        if (a.resources.configuration.locales[0].language == "ru") ru else en

    fun showPicker(a: Activity, changed: () -> Unit) {
        val profiles=DeviceProfiles.all(a)
        val labels=listOf(t(a,"Редактировать текущий / создать свой","Edit current / create custom")) +
            profiles.map { DeviceProfiles.title(a,it) + if(it.hwid.isNotEmpty()) " · HWID …${it.hwid.takeLast(8)}" else "" } + t(a,"Выключить профиль","Disable profile")
        val picker=AnimatedDialogBuilder(a).setTitle(a.getString(R.string.device_profile_choose))
        picker.setItems(labels.toTypedArray()) { _, index ->
                when(index) {
                    0 -> showEditor(a,DeviceProfiles.active(a) ?: DeviceProfiles.Preset(name=t(a,"Мой профиль","My profile")),changed)
                    labels.lastIndex -> { DeviceProfiles.disable(a); changed() }
                    else -> showEditor(a,profiles[index-1],changed)
                }
            }
        picker.showAnimated()
    }

    fun showEditor(a: Activity, profile: DeviceProfiles.Preset, changed: () -> Unit) {
        val p=profile.copy(name=DeviceProfiles.title(a,profile),hwid=if(profile.sendHwid) DeviceProfiles.hwid(a,profile) else profile.hwid)
        val root=a.layoutInflater.inflate(R.layout.dialog_subscription_identity,null)
        val column=(root as ScrollView).getChildAt(0) as LinearLayout
        fun input(id: Int)=root.findViewById<TextInputEditText>(id)
        val os=input(R.id.inputDeviceOs); val version=input(R.id.inputDeviceOsVersion)
        val model=input(R.id.inputDeviceModel); val name=input(R.id.inputDeviceName)
        val ua=input(R.id.inputIdentityUa); val hwid=input(R.id.inputIdentityHwid); val hosts=input(R.id.inputIdentityHosts)
        os.setText(p.os); version.setText(p.version); model.setText(p.model); name.setText(p.name)
        ua.setText(p.userAgent); hwid.setText(p.hwid); hosts.setText(p.hosts)
        root.findViewById<View>(R.id.btnIdentityPreset).visibility = View.GONE
        fun field(label: String, value: String, multi: Boolean=false): TextInputEditText {
            val layout=TextInputLayout(a).apply { hint=label }
            val edit=TextInputEditText(a).apply { setText(value); setSingleLine(!multi); if(multi) minLines=3 }
            layout.addView(edit); column.addView(layout); return edit
        }
        val client=field(t(a,"Клиент","Client"),p.client)
        val clientVersion=field(t(a,"Версия клиента (не формирует UA автоматически)","Client version (does not generate UA)"),p.clientVersion)
        val sendHwid=CheckBox(a).apply { text=t(a,"Отправлять HWID","Send HWID"); isChecked=p.sendHwid }
        val deviceHeaders=CheckBox(a).apply { text=t(a,"Отправлять ОС и модель: пользовательский формат x-device-os / x-ver-os / x-device-model","Send OS/model: custom x-device-os / x-ver-os / x-device-model schema"); isChecked=p.deviceHeaders }
        column.addView(sendHwid); column.addView(deviceHeaders)
        val nativeHwid=CheckBox(a).apply { text=t(a,"Также подменять HWID через API в поддерживаемых приложениях (при включённой отправке HWID)","Also spoof HWID APIs in supported apps (requires Send HWID)"); isChecked=p.nativeHwid }
        column.addView(nativeHwid)
        val hwidHeader=field(t(a,"Имя HTTP-заголовка HWID","HWID HTTP header name"),p.hwidHeader)
        val format=Spinner(a)
        val formats=listOf("uuid","hex32","hex16")
        format.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,listOf("UUID (36)","HEX (32)","HEX (16)"))
        format.setSelection(formats.indexOf(p.hwidFormat).coerceAtLeast(0))
        column.addView(TextView(a).apply { text=t(a,"Формат генератора HWID; существующее значение не меняется","HWID generator format; existing value stays unchanged") })
        column.addView(format)
        val headers=field(t(a,"Дополнительные заголовки: JSON, строковые значения","Extra headers: JSON with string values"),JSONObject(p.headers).toString(2),true)
        val note=TextView(a).apply {
            text=when(p.evidence) {
                "compatibility" -> t(a,"Happ: подтверждена версия. Родной UA и текущий формат заголовков неизвестны. Здесь выбран редактируемый формат совместимости; сервер может его не учитывать.","Happ: version verified. Native UA and current headers are unknown. This is an editable compatibility schema; a server may ignore it.")
                "source" -> t(a,"UA по исходнику выпуска; настройки реального клиента могут его менять. Фактический запрос не проверялся.","UA derived from release source; client settings may override it. No live request verification.")
                else -> t(a,"Пользовательский / перенесённый профиль. Проверяйте предпросмотр запроса.","Custom / migrated profile. Review outgoing headers.")
            } + if(p.source.isNotEmpty()) "\n"+p.source else ""
        }
        column.addView(note,0)
        val preview=root.findViewById<TextView>(R.id.textIdentityPreview)
        // The exact preview belongs after every editable option.
        column.removeView(preview); column.addView(preview)
        fun draft(): DeviceProfiles.Preset {
            val raw=hosts.text.toString().split(Regex("[,;\\r\\n]+"))
            val normalized=raw.map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.map {
                val host=java.net.IDN.toASCII(it).lowercase(java.util.Locale.ROOT)
                require(host.length<=253 && host.split('.').all { label -> label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) { "Invalid exact hostname" }
                host
            }.distinct().joinToString(",")
            return p.copy(name=name.text.toString().trim(),os=os.text.toString(),version=version.text.toString(),model=model.text.toString(),
                userAgent=ua.text.toString(),hwid=hwid.text.toString(),client=client.text.toString(),clientVersion=clientVersion.text.toString(),
                sendHwid=sendHwid.isChecked,nativeHwid=nativeHwid.isChecked,deviceHeaders=deviceHeaders.isChecked,hwidHeader=hwidHeader.text.toString(),
                hwidFormat=formats[format.selectedItemPosition],headers=IdentityCodec.headers(JSONObject(headers.text.toString())),hosts=normalized)
        }
        fun preview() {
            preview.text=runCatching {
                val identity=draft().identity()
                val errors=identity.invalidHeaderFields()
                if(errors.isNotEmpty()) "Invalid: "+errors.joinToString() else identity.subscriptionHeaders().entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    .ifEmpty { t(a,"Нет переопределённых заголовков. HTTP-библиотека может добавить свои стандартные заголовки.","No header overrides. The HTTP library may add its own defaults.") }
            }.getOrElse { it.message ?: "Invalid profile" }
        }
        listOf(os,version,model,name,ua,hwid,hosts,client,clientVersion,hwidHeader,headers).forEach { it.doAfterTextChanged { preview() } }
        sendHwid.setOnCheckedChangeListener { _, _ -> preview() }; deviceHeaders.setOnCheckedChangeListener { _, _ -> preview() }
        root.findViewById<View>(R.id.btnGenerateProfileHwid).setOnClickListener {
            AnimatedDialogBuilder(a).setMessage(t(a,"Создать новый HWID? Идентификатор этого профиля изменится после сохранения.","Generate a new HWID? Saving will change this profile's identifier."))
                .setPositiveButton(a.getString(android.R.string.ok)) { _, _ -> hwid.setText(DeviceProfiles.generateHwid(formats[format.selectedItemPosition])) }
                .setNegativeButton(a.getString(android.R.string.cancel),null).showAnimated()
        }
        val custom=DeviceProfiles.isSaved(a,p.id)
        val dialog=AnimatedDialogBuilder(a).setTitle(t(a,"Профиль запроса","Request profile")).setView(root)
            .setPositiveButton(t(a,"Сохранить и применить","Save and apply"),null)
            .setNegativeButton(a.getString(android.R.string.cancel),null)
            .setNeutralButton(if(custom) t(a,"Удалить","Delete") else t(a,"Создать копию","Create copy"),null).showAnimated()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val result=draft()
                if(!custom && result.id.isNotBlank() && result==p) DeviceProfiles.apply(a,result) else DeviceProfiles.save(a,result)
                changed(); dialog.dismiss()
            } catch(e: Exception) { Toast.makeText(a,e.message ?: "Invalid profile",Toast.LENGTH_LONG).show() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if(custom) AnimatedDialogBuilder(a).setMessage(t(a,"Удалить профиль?","Delete this profile?"))
                .setPositiveButton(a.getString(android.R.string.ok)) { _, _ -> DeviceProfiles.delete(a,p.id); changed(); dialog.dismiss() }
                .setNegativeButton(a.getString(android.R.string.cancel),null).showAnimated()
            else {
                try { val d=draft(); val copy=d.copy(id="",name=p.name+t(a," (копия)"," (copy)"),hwid=if(d.sendHwid) DeviceProfiles.generateHwid(d.hwidFormat) else ""); dialog.dismiss(); showEditor(a,copy,changed) }
                catch(e: Exception) { Toast.makeText(a,e.message,Toast.LENGTH_LONG).show() }
            }
        }
        preview()
    }

    fun showHwidEditor(a: Activity, changed: () -> Unit) {
        val profile = DeviceProfiles.active(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val layout = TextInputLayout(a).apply { hint = "HWID" }
        val edit = TextInputEditText(a).apply {
            setSingleLine(true)
            setText(DeviceProfiles.currentIdentity(a).hwid)
        }
        layout.addView(edit); root.addView(layout)
        root.addView(Button(a).apply {
            text = t(a, "Сгенерировать новый HWID", "Generate new HWID")
            setOnClickListener { edit.setText(DeviceProfiles.generateHwid(profile?.hwidFormat ?: "hex16")) }
        })
        val dialog = AnimatedDialogBuilder(a)
            .setTitle(t(a, "Изменить HWID", "Edit HWID"))
            .setView(root)
            .setPositiveButton(t(a, "Сохранить", "Save"), null)
            .setNegativeButton(a.getString(android.R.string.cancel), null).showAnimated()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                DeviceProfiles.updateHwid(a, edit.text.toString())
                changed(); dialog.dismiss()
            } catch (e: Exception) { layout.error = e.message }
        }
    }
}
