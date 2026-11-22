package com.darknight.miuisettingmod.ui.component.pref

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darknight.miuisettingmod.R
import com.darknight.miuisettingmod.prefs.ConfigState
import com.darknight.miuisettingmod.prefs.OptionSpec
import com.darknight.miuisettingmod.xposed.HookStatusStore
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import com.darknight.miuisettingmod.ui.component.AppArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 副标题：基础文案。
 *
 * 生效状态徽标已按需求全部隐藏，摘要只展示配置项本身的说明。
 */
@Composable
private fun optionSummary(spec: OptionSpec): String? =
    spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) }

/**
 * 大标题。
 *
 * 生效状态徽标已按需求全部隐藏（不再读取 HookStatusStore）；
 * 标题字号 / 字重与 Miuix `BasicComponent` 默认标题一致。
 */
@Composable
private fun HookTitle(
    title: String,
    enabled: Boolean,
) {
    MiuixText(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = if (enabled) {
            MiuixTheme.colorScheme.onBackground
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
    )
}

@Composable
private fun HookSummary(text: String?, enabled: Boolean) {
    if (text == null) return
    MiuixText(
        text = text,
        modifier = Modifier.padding(top = 2.dp),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = if (enabled) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
    )
}

/** 带 switch 的卡片：关时不 hook，开时 hook。 */
@Composable
fun HookSwitchCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)

    fun update(value: Boolean) {
        ConfigState.set(spec.key, value)
        // 开关变化后需重启目标进程才会生效；先清除旧证据，待其重新回报。
        HookStatusStore.removeKeys(context, listOf(spec.key))
        if (value) ensureScopeFor(spec)
    }

    BasicComponent(
        modifier = modifier,
        enabled = enabled,
        onClick = { update(!checked) },
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = { update(it) },
                enabled = enabled,
            )
        },
    ) {
        HookTitle(
            title = stringResource(spec.titleRes),
            enabled = enabled,
        )
        HookSummary(text = optionSummary(spec), enabled = enabled)
    }
}

/** 带 checkbox 的卡片。 */
@Composable
fun HookCheckboxCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)

    fun update(value: Boolean) {
        ConfigState.set(spec.key, value)
        HookStatusStore.removeKeys(context, listOf(spec.key))
        if (value) ensureScopeFor(spec)
    }

    BasicComponent(
        modifier = modifier,
        enabled = enabled,
        onClick = { update(!checked) },
        endActions = {
            Checkbox(
                state = ToggleableState(checked),
                onClick = { update(!checked) },
                enabled = enabled,
            )
        },
    ) {
        HookTitle(
            title = stringResource(spec.titleRes),
            enabled = enabled,
        )
        HookSummary(text = optionSummary(spec), enabled = enabled)
    }
}

/** 带 arrow 的卡片：点击进入下级页面。 */
@Composable
fun HookArrowCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val enabled = rememberOptionEnabled(spec)
    AppArrowPreference(
        title = stringResource(spec.titleRes),
        summary = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    )
}
