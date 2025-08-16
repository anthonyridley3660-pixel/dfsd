
package com.shiftguard

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Entity
data class AppSettings(@PrimaryKey val id: Int = 1, val shiftStart: String = "09:00", val shiftEnd: String = "17:00", val graceMinutes: Int = 5, val selfieRequired: Boolean = true)
@Entity data class ShiftTemplate(@PrimaryKey(autoGenerate = true) val id: Int = 0, val name: String, val start: String, val end: String, val grace: Int)
@Entity data class AttendanceLog(@PrimaryKey(autoGenerate = true) val id: Int = 0, val name: String, val timestamp: Long, val type: String, val selfiePath: String? = null, val isLate: Boolean = false, val open: Boolean = true)

@Dao interface AppDao {
    @Query("SELECT * FROM AppSettings WHERE id = 1") suspend fun getSettings(): AppSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: AppSettings)
    @Query("SELECT * FROM ShiftTemplate ORDER BY name ASC") suspend fun getTemplates(): List<ShiftTemplate>
    @Insert suspend fun addTemplate(t: ShiftTemplate)
    @Delete suspend fun deleteTemplate(t: ShiftTemplate)
    @Query("SELECT * FROM AttendanceLog ORDER BY timestamp DESC") suspend fun allLogs(): List<AttendanceLog>
    @Insert suspend fun addLog(log: AttendanceLog)
    @Update suspend fun updateLog(log: AttendanceLog)
    @Delete suspend fun deleteLog(log: AttendanceLog)
    @Query("DELETE FROM AttendanceLog") suspend fun clearAll()
    @Query("DELETE FROM AttendanceLog WHERE date(timestamp/1000,'unixepoch') = date('now')") suspend fun clearToday()
    @Query("DELETE FROM AttendanceLog WHERE timestamp >= :from") suspend fun clearFrom(from: Long)
}

@Database(entities=[AppSettings::class, ShiftTemplate::class, AttendanceLog::class], version=1)
abstract class AppDB: RoomDatabase(){ abstract fun dao(): AppDao
    companion object { @Volatile private var I: AppDB?=null
        fun get(ctx: Context)= I?: synchronized(this){ I?: Room.databaseBuilder(ctx, AppDB::class.java, "shiftguard.db").build().also{ I=it } }
    }
}

class MainVM(private val ctx: Context): ViewModel(){
    private val dao = AppDB.get(ctx).dao()
    var settings by mutableStateOf(AppSettings()); private set
    var logs by mutableStateOf(listOf<AttendanceLog>()); private set
    var templates by mutableStateOf(listOf<ShiftTemplate>()); private set
    init { viewModelScope.launch { loadAll() } }
    suspend fun loadAll(){ withContext(Dispatchers.IO){ settings = dao.getSettings() ?: AppSettings().also { dao.saveSettings(it) }; logs = dao.allLogs(); templates = dao.getTemplates() } }
    fun saveSettings(s: AppSettings)= viewModelScope.launch(Dispatchers.IO){ dao.saveSettings(s); settings = s }
    fun addTemplate(n:String, st:String, en:String, g:Int)= viewModelScope.launch(Dispatchers.IO){ dao.addTemplate(ShiftTemplate(name=n,start=st,end=en,grace=g)); templates = dao.getTemplates() }
    fun deleteTemplate(t:ShiftTemplate)= viewModelScope.launch(Dispatchers.IO){ dao.deleteTemplate(t); templates = dao.getTemplates() }
    fun sign(name:String, selfie:Bitmap?, type:String)= viewModelScope.launch(Dispatchers.IO){
        val ts = System.currentTimeMillis(); val late = type=="IN" && isLate(ts, settings); val path = selfie?.let{ saveSelfie(it, name, ts) }
        dao.addLog(AttendanceLog(name=name.lowercase(), timestamp=ts, type=type, selfiePath=path, isLate=late, open= type=="IN")); logs = dao.allLogs()
    }
    fun signOutOpenEntry(name:String)= viewModelScope.launch(Dispatchers.IO){
        val open = dao.allLogs().firstOrNull{ it.open && it.name==name.lowercase() }
        open?.let{ dao.updateLog(it.copy(open=false, type="OUT", timestamp=System.currentTimeMillis())); logs = dao.allLogs() }
    }
    fun renameLog(log:AttendanceLog,newName:String)= viewModelScope.launch(Dispatchers.IO){ dao.updateLog(log.copy(name=newName.lowercase())); logs = dao.allLogs() }
    fun deleteLog(log:AttendanceLog)= viewModelScope.launch(Dispatchers.IO){ dao.deleteLog(log); logs = dao.allLogs() }
    fun clearRange(which:String)= viewModelScope.launch(Dispatchers.IO){
        when(which){ "day"->dao.clearToday(); "week"->dao.clearFrom(System.currentTimeMillis()-6L*24*3600*1000); "month"->dao.clearFrom(System.currentTimeMillis()-29L*24*3600*1000); "all"->dao.clearAll() }
        logs = dao.allLogs()
    }
    suspend fun exportCsv(includeSelfies:Boolean): Uri? = withContext(Dispatchers.IO){
        val csv = buildString{
            appendLine("id,name,timestamp,iso,type,isLate,open,selfiePath")
            dao.allLogs().forEach{
                appendLine(listOf(it.id,it.name,it.timestamp, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date(it.timestamp)), it.type,it.isLate,it.open,it.selfiePath?:"").joinToString(","))
            }
        }.toByteArray()
        val resolver = ctx.contentResolver; val fname="bluenine_"+SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val csvUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply{ put(MediaStore.Downloads.DISPLAY_NAME,"$fname.csv"); put(MediaStore.Downloads.MIME_TYPE,"text/csv") })?.also{ uri -> resolver.openOutputStream(uri)?.use{ it.write(csv) } }
        if (!includeSelfies) return@withContext csvUri
        val zipUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply{ put(MediaStore.Downloads.DISPLAY_NAME,"$fname.zip"); put(MediaStore.Downloads.MIME_TYPE,"application/zip") })
        zipUri?.let{ uri -> resolver.openOutputStream(uri)?.use{ out -> ZipOutputStream(out).use{ zip ->
            zip.putNextEntry(ZipEntry("$fname.csv")); zip.write(csv); zip.closeEntry()
            dao.allLogs().mapNotNull{ it.selfiePath }.distinct().forEach{ p -> val f=File(p); if (f.exists()){ zip.putNextEntry(ZipEntry("selfies/${f.name}")); f.inputStream().use{ it.copyTo(zip) }; zip.closeEntry() } }
        } } }
        return@withContext zipUri
    }
    suspend fun exportExcelZip(): Uri? = withContext(Dispatchers.IO){
        val rows = dao.allLogs(); val fname="bluenine_"+SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val xml = buildExcelXml(rows).toByteArray(Charset.forName("UTF-8"))
        val resolver = ctx.contentResolver
        val zipUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply{ put(MediaStore.Downloads.DISPLAY_NAME, fname+"-excel.zip"); put(MediaStore.Downloads.MIME_TYPE, "application/zip") })
        zipUri?.let{ uri -> resolver.openOutputStream(uri)?.use{ out -> ZipOutputStream(out).use{ zip ->
            zip.putNextEntry(ZipEntry("$fname.xls")); zip.write(xml); zip.closeEntry()
            rows.mapNotNull{ it.selfiePath }.distinct().forEach{ p -> val f=File(p); if (f.exists()){ zip.putNextEntry(ZipEntry("selfies/${f.name}")); f.inputStream().use{ it.copyTo(zip) }; zip.closeEntry() } }
        } } }
        return@withContext zipUri
    }
    private fun buildExcelXml(rows: List<AttendanceLog>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">
 <Worksheet ss:Name="Attendance">
  <Table>
   <Row>
    <Cell><Data ss:Type="String">id</Data></Cell>
    <Cell><Data ss:Type="String">name</Data></Cell>
    <Cell><Data ss:Type="String">timestamp</Data></Cell>
    <Cell><Data ss:Type="String">iso</Data></Cell>
    <Cell><Data ss:Type="String">type</Data></Cell>
    <Cell><Data ss:Type="String">isLate</Data></Cell>
    <Cell><Data ss:Type="String">open</Data></Cell>
    <Cell><Data ss:Type="String">selfiePath</Data></Cell>
   </Row>
""")
        rows.forEach {
            sb.append("   <Row>")
            sb.append("<Cell><Data ss:Type=\"Number\">${it.id}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${it.name}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"Number\">${it.timestamp}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${fmt.format(Date(it.timestamp))}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${it.type}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${it.isLate}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${it.open}</Data></Cell>")
            sb.append("<Cell><Data ss:Type=\"String\">${it.selfiePath ?: ""}</Data></Cell>")
            sb.append("</Row>\n")
        }
        sb.append("""  </Table>
 </Worksheet>
</Workbook>""")
        return sb.toString()
    }
    private fun isLate(ts:Long, s:AppSettings):Boolean{
        val cal = Calendar.getInstance(); val (hh,mm) = s.shiftStart.split(":").map{ it.toInt() }
        cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis + s.graceMinutes*60_000L; return ts>start
    }
    private fun saveSelfie(bmp:Bitmap, name:String, ts:Long): String? = try {
        val dir = File(ctx.filesDir, "selfies").apply{ mkdirs() }
        val f = File(dir, "${name}_${ts}.jpg")
        FileOutputStream(f).use{ bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    } catch (_:Exception){ null }
}

class MainVMFactory(private val ctx: Context): ViewModelProvider.Factory {
    override fun <T: ViewModel> create(modelClass: Class<T>): T = MainVM(ctx.applicationContext) as T
}

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm: MainVM by viewModels { MainVMFactory(this) }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppScaffold(vm) } }
    }
}

@Composable fun AppScaffold(vm: MainVM) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = { TopBar() },
        bottomBar = {
            NavigationBar { listOf("Sign","Admin").forEachIndexed { i,t ->
                NavigationBarItem(selected = tab==i, onClick = { tab=i }, label = { Text(t) }, icon = { })
            } }
        }
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize().background(Color(0xFF0F131A))) {
            if (tab==0) SignScreen(vm) else AdminGate { AdminScreen(vm) }
        }
    }
}

@Composable fun TopBar() {
    TopAppBar(title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(id = R.drawable.logo_bluenine), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Blue Nine Access", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable fun AdminGate(content: @Composable () -> Unit) {
    var ok by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(!ok) }
    if (!ok) { if (show) PasswordDialog(onOk = { if (it=="Bluenin1"){ ok=true; show=false } }, onCancel = { show=false }) }
    if (ok) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { show = true }) { Text("Enter Admin Password") } }
}

@Composable fun PasswordDialog(onOk:(String)->Unit, onCancel:()->Unit){
    var p by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = { onOk(p) }){ Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel){ Text("Cancel") } },
        title = { Text("Admin Login") },
        text = { OutlinedTextField(value=p, onValueChange={ p=it }, label={ Text("Password") }, visualTransformation = PasswordVisualTransformation()) }
    )
}

@Composable fun SignScreen(vm: MainVM){
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    val settings = vm.settings
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted ->
        if (granted) takePicture.launch(null) else vm.sign(name.trim(), null, "IN")
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){ bmp ->
        if (bmp!=null) vm.sign(name.trim(), bmp, "IN")
    }
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Text("Sign In / Out", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value=name, onValueChange={ name=it }, label={ Text("Name") }, modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Shift: ${settings.shiftStart} - ${settings.shiftEnd} | Grace: ${settings.graceMinutes}m", color = Color.LightGray)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)){
            Button(modifier=Modifier.weight(1f), enabled=name.isNotBlank(), onClick={
                if (!settings.selfieRequired) vm.sign(name.trim(), null, "IN") else {
                    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
                    if (granted) takePicture.launch(null) else requestCamera.launch(Manifest.permission.CAMERA)
                }
            }){ Text(if (settings.selfieRequired) "Sign In (Selfie)" else "Sign In") }
            OutlinedButton(modifier=Modifier.weight(1f), enabled=name.isNotBlank(), onClick={ vm.signOutOpenEntry(name.trim().lowercase()) }){ Text("Sign Out") }
        }
        Spacer(Modifier.height(16.dp)); Divider(); Spacer(Modifier.height(8.dp))
        Text("Recent", color = Color.White); Spacer(Modifier.height(8.dp))
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)){
            items(vm.logs.take(20)){ log ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()){
                    if (log.selfiePath != null) AsyncImage(model = File(log.selfiePath), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text("${log.name} ${log.type.lowercase()} ${fmt.format(Date(log.timestamp))} ${if (log.isLate) "(Late)" else ""}", color = Color.White)
                }
            }
        }
    }
}

@Composable fun AdminScreen(vm: MainVM){
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(vm.settings) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)){
        item { Text("Admin Panel", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)){
                OutlinedButton(onClick = { scope.launch { vm.exportCsv(false) } }){ Text("Export CSV") }
                Button(onClick = { scope.launch { vm.exportCsv(true) } }){ Text("Export CSV + Selfies") }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)){ Button(onClick = { scope.launch { vm.exportExcelZip() } }){ Text("Export ZIP (Excel + Selfies)") } } }
        item { Text("Clear Attendance Logs", color = Color.White) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)){
            Button(onClick = { vm.clearRange("day") }){ Text("By Day") }
            Button(onClick = { vm.clearRange("week") }){ Text("By Week") }
            Button(onClick = { vm.clearRange("month") }){ Text("By Month") }
            Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAFAF)), onClick = { vm.clearRange("all") }){ Text("Clear ALL") }
        } }
        item { Text("Shift Settings", fontSize = 22.sp, color = Color.White) }
        item { OutlinedTextField(value = s.shiftStart, onValueChange = { s = s.copy(shiftStart = it) }, label = { Text("Shift Start (HH:mm)") }) }
        item { OutlinedTextField(value = s.shiftEnd, onValueChange = { s = s.copy(shiftEnd = it) }, label = { Text("Shift End (HH:mm)") }) }
        item { OutlinedTextField(value = s.graceMinutes.toString(), onValueChange = { v -> v.toIntOrNull()?.let { s = s.copy(graceMinutes = it) } }, label = { Text("Grace Minutes") }) }
        item { Row(verticalAlignment = Alignment.CenterVertically){ Checkbox(checked = s.selfieRequired, onCheckedChange = { s = s.copy(selfieRequired = it) }); Text("Selfie Required", color = Color.White) } }
        item { Button(onClick = { vm.saveSettings(s) }){ Text("Save Settings") } }
        item { Divider() }
        item { Text("Shift Templates", fontSize = 22.sp, color = Color.White) }
        item { ShiftTemplatesSection(vm) }
        item { Divider() }
        item { Text("All Attendance Logs", fontSize = 22.sp, color = Color.White) }
        items(vm.logs){ log -> LogRow(vm, log) }
    }
}

@Composable fun ShiftTemplatesSection(vm: MainVM){
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(vm.settings.shiftStart) }
    var end by remember { mutableStateOf(vm.settings.shiftEnd) }
    var grace by remember { mutableStateOf(vm.settings.graceMinutes.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)){
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()){
            OutlinedTextField(value=name, onValueChange={ name=it }, label={ Text("Name") }, modifier=Modifier.weight(1f))
            OutlinedTextField(value=start, onValueChange={ start=it }, label={ Text("Start HH:mm") }, modifier=Modifier.weight(1f))
            OutlinedTextField(value=end, onValueChange={ end=it }, label={ Text("End HH:mm") }, modifier=Modifier.weight(1f))
            OutlinedTextField(value=grace, onValueChange={ if (it.all{ c->c.isDigit() }) grace=it }, label={ Text("Grace") }, modifier=Modifier.width(90.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)){
            Button(onClick = { if (name.isNotBlank()) vm.addTemplate(name.trim(), start.trim(), end.trim(), grace.toIntOrNull()?:5) }){ Text("Create Shift") }
        }
        Spacer(Modifier.height(8.dp))
        vm.templates.forEach{ t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween){
                Text("${t.name}: ${t.start}-${t.end} • Grace ${t.grace}m", color = Color.White)
                Row{
                    TextButton(onClick = { vm.saveSettings(AppSettings(1, t.start, t.end, t.grace, vm.settings.selfieRequired)) }){ Text("Apply") }
                    TextButton(onClick = { vm.deleteTemplate(t) }){ Text("Delete") }
                }
            }
        }
    }
}

@Composable fun LogRow(vm: MainVM, log: AttendanceLog){
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically){
        if (log.selfiePath != null) AsyncImage(model = File(log.selfiePath), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)){
            Text(log.name, color = Color.White, fontWeight = FontWeight.Bold)
            Text("${log.type} • ${fmt.format(Date(log.timestamp))} • ${if (log.open) "OPEN" else "closed"}${if (log.isLate) " • late" else ""}", color = Color.LightGray, fontSize = 12.sp)
        }
        var renaming by remember { mutableStateOf(false) }
        if (renaming) RenameDialog(current = log.name, onOk = { vm.renameLog(log, it); renaming = false }, onCancel = { renaming = false })
        Text("Rename", color = Color(0xFF7FB4FF), modifier = Modifier.clickable { renaming = true }.padding(8.dp))
        Spacer(Modifier.width(8.dp))
        Text("Delete", color = Color(0xFFFF7F7F), modifier = Modifier.clickable { vm.deleteLog(log) }.padding(8.dp))
    }
}

@Composable fun RenameDialog(current:String, onOk:(String)->Unit, onCancel:()->Unit){
    var v by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = { onOk(v) }){ Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel){ Text("Cancel") } },
        title = { Text("Rename Entry") },
        text = { OutlinedTextField(value=v, onValueChange={ v=it }, label={ Text("Name") }) }
    )
}
