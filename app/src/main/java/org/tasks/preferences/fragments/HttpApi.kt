package org.tasks.preferences.fragments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.compose.settings.HttpApiScreen
import org.tasks.themes.TasksSettingsTheme
import org.tasks.themes.Theme
import javax.inject.Inject

@AndroidEntryPoint
class HttpApi : Fragment() {

    @Inject lateinit var theme: Theme

    private val viewModel: HttpApiViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ) = content {
        TasksSettingsTheme(
            theme = theme.themeBase.index,
            primary = theme.themeColor.primaryColor,
        ) {
            HttpApiScreen(
                running = viewModel.running,
                port = viewModel.port,
                error = viewModel.error,
                password = viewModel.password,
                onStart = { viewModel.start() },
                onStop = { viewModel.stop() },
                onPasswordChange = { viewModel.setPassword(it) },
                onPortChange = { viewModel.setPort(it) },
            )
        }
    }
}
