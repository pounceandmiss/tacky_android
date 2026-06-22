package com.example.tackyapk.feature.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.core.content.FileProvider
import com.example.tackyapk.FileCache
import com.example.tackyapk.rememberFile
import com.example.tackyapk.R
import java.io.File
import java.net.URLConnection

/** Inline rendering of a message attachment: an image thumbnail for images,
 *  a file card otherwise. Drives [FileCache] to download/resolve on demand. */
@Composable
fun AttachmentView(
    cache: FileCache,
    acc: String,
    attachment: Attachment,
    modifier: Modifier = Modifier,
) {
    val isImage = attachment.mime.startsWith("image/", ignoreCase = true) ||
        attachment.type.equals("image", ignoreCase = true)
    val fileState = rememberFile(cache, acc, attachment.url)
    val context = LocalContext.current
    // Clicking opens the cached copy with the OS, mirroring the desktop GUI's
    // AttachOpen. The download is already in flight from rememberFile's track,
    // so localPath is populated once it finishes; until then, say so.
    val onOpen = {
        val local = fileState.localPath
        if (local != null) {
            openWithOs(context, local, attachment.mime)
        } else {
            Toast.makeText(context, "Still downloading…", Toast.LENGTH_SHORT).show()
        }
    }

    if (isImage) {
        val path = fileState.thumbPath ?: fileState.localPath
        val bitmap = remember(path) {
            if (path != null) BitmapFactory.decodeFile(path) else null
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name.ifEmpty { lastSegment(attachment.url) },
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 240.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen() },
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = modifier.size(width = 200.dp, height = 140.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (fileState.state) {
                        "active" -> CircularProgressIndicator()
                        "failed" -> Text(
                            "Couldn't load image",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = modifier.clickable { onOpen() },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_insert_drive_file), contentDescription = null)
                Column(modifier = Modifier.widthIn(max = 200.dp)) {
                    Text(
                        attachment.name.ifEmpty { lastSegment(attachment.url) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val caption = caption(attachment.size, attachment.mime)
                    if (caption.isNotEmpty()) {
                        Text(
                            caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (fileState.state == "active") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Hand the cached file to whatever app can view its type, via a content:// URI
 *  with a one-shot read grant (a raw file:// would throw FileUriExposedException). */
private fun openWithOs(context: Context, localPath: String, mime: String) {
    val file = File(localPath)
    if (!file.exists()) {
        Toast.makeText(context, "File is no longer available", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val type = mime.ifEmpty { URLConnection.guessContentTypeFromName(file.name) ?: "*/*" }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        // NEW_TASK lets the chooser launch from a non-Activity context too.
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun caption(size: Long, mime: String): String {
    val parts = listOf(formatSize(size), mime).filter { it.isNotEmpty() }
    return parts.joinToString(" - ")
}

private fun lastSegment(url: String): String =
    url.substringBefore("?").substringBefore("#").substringAfterLast('/')

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes < kb -> "$bytes B"
        bytes < mb -> String.format("%.1f KB", bytes / kb)
        else -> String.format("%.1f MB", bytes / mb)
    }
}
