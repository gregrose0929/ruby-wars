// Ruby Wars Setup: installs and updates the Ruby Wars modpack for the official Minecraft Launcher.
//
// Built by tools/installer/build.sh with the C# compiler that comes with Windows (.NET Framework 4), so it runs on
// any Windows 10/11 PC with nothing else installed. (C# 5: no string interpolation or ?. here.)
//
// Run it once: it installs. After that it lives in %LOCALAPPDATA%\Programs\Ruby Wars with a "Ruby Wars" shortcut on
// the desktop and in the Start menu; the shortcut runs it with --play, which updates the pack if there's a new one and
// opens the launcher.
//
// What it does, the same each time:
//   1. Asks GitHub for the newest Ruby Wars release, and downloads its modpack (ruby-wars.mrpack).
//   2. Puts the pack's mods in their own game folder (%APPDATA%\.rubywars): nothing else you play is touched.
//      Mods already there are checked, not downloaded again; ones the pack dropped are removed.
//   3. Adds Fabric and a "Ruby Wars" entry to the Minecraft Launcher, the Ruby Wars server to the multiplayer list,
//      and (the first time only) friendly game settings.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Ruby Wars Setup")]
[assembly: System.Reflection.AssemblyProduct("Ruby Wars")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace RubyWars {
	static class Program {
		[System.Runtime.InteropServices.DllImport("user32.dll")]
		static extern bool SetProcessDPIAware();

		[STAThread]
		static void Main(string[] args) {
			// Sharp on high-resolution screens (Windows would otherwise stretch a blurry picture of it).
			try {
				SetProcessDPIAware();
			} catch (Exception) {
			}
			Application.EnableVisualStyles();
			Application.SetCompatibleTextRenderingDefault(false);
			bool play = args.Any(a => a.Equals("--play", StringComparison.OrdinalIgnoreCase));
			Application.Run(new SetupForm(play));
		}
	}

	class SetupForm : Form {
		const string Repo = "gregrose0929/ruby-wars";
		static readonly Color Bg = Color.FromArgb(22, 24, 29);
		static readonly Color Card = Color.FromArgb(32, 35, 42);
		static readonly Color Ruby = Color.FromArgb(214, 32, 62);
		static readonly Color Ink = Color.FromArgb(236, 232, 226);
		static readonly Color Muted = Color.FromArgb(150, 156, 168);

		readonly bool playMode;
		// (Other folders, and no shortcuts or launcher, for testing: RW_MCDIR, RW_GAMEDIR, RW_INSTALLDIR, RW_TEST=1.)
		readonly string mcDir = Env("RW_MCDIR", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft"));
		readonly string gameDir = Env("RW_GAMEDIR", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".rubywars"));
		readonly string installDir = Env("RW_INSTALLDIR", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs",
				"Ruby Wars"));
		static readonly bool Testing = Environment.GetEnvironmentVariable("RW_TEST") == "1";
		/** What the big button does right now. */
		Action onMain;

		static string Env(string name, string fallback) {
			string v = Environment.GetEnvironmentVariable(name);
			return string.IsNullOrEmpty(v) ? fallback : v;
		}

		readonly Label status = new Label();
		readonly Label detail = new Label();
		readonly ProgressBar bar = new ProgressBar();
		readonly Button main = new Button();
		readonly Button close = new Button();
		bool busy;
		/** Screen scale (1 at 100%, 1.5 at 150%...): sizes below are at 100% and multiplied by it. */
		float k = 1;

		int S(int v) {
			return (int) Math.Round(v * k);
		}

		public SetupForm(bool play) {
			playMode = play;
			Text = "Ruby Wars";
			AutoScaleMode = AutoScaleMode.None;
			using (var g = CreateGraphics()) {
				k = g.DpiX / 96f;
			}
			ClientSize = new Size(S(520), S(300));
			MinimizeBox = true;
			FormBorderStyle = FormBorderStyle.FixedSingle;
			MaximizeBox = false;
			StartPosition = FormStartPosition.CenterScreen;
			BackColor = Bg;
			ForeColor = Ink;
			Font = new Font("Segoe UI", 10f);
			try {
				Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
			} catch (Exception) {
			}

			var header = new Panel { Dock = DockStyle.Top, Height = S(100), BackColor = Card };
			header.Paint += delegate(object s, PaintEventArgs e) {
				e.Graphics.FillRectangle(new SolidBrush(Ruby), 0, header.Height - S(4), header.Width, S(4));
				DrawGem(e.Graphics, S(26), S(24), S(48));
			};
			var title = new Label { Text = "RUBY WARS", Font = new Font("Segoe UI Black", 22f, FontStyle.Bold), ForeColor = Ruby, AutoSize = false,
				Bounds = new Rectangle(S(86), S(12), S(420), S(48)), BackColor = Color.Transparent };
			var sub = new Label { Text = "The family server, ready to play", ForeColor = Muted, AutoSize = false, Bounds = new Rectangle(S(90), S(62), S(400), S(24)),
				BackColor = Color.Transparent };
			header.Controls.Add(title);
			header.Controls.Add(sub);
			Controls.Add(header);

			status.AutoSize = false;
			detail.AutoSize = false;
			status.SetBounds(S(28), S(116), S(464), S(28));
			status.Font = new Font("Segoe UI Semibold", 12f);
			status.Text = play ? "Checking for updates..." : "Ready to install Ruby Wars.";
			detail.SetBounds(S(28), S(146), S(464), S(46));
			detail.ForeColor = Muted;
			detail.Text = play ? "" : "This sets up the Minecraft Launcher for Ruby Wars and puts a Ruby Wars shortcut on your desktop. "
					+ "Nothing else you play is changed.";
			bar.SetBounds(S(28), S(198), S(464), S(14));
			bar.Style = ProgressBarStyle.Continuous;
			Controls.Add(status);
			Controls.Add(detail);
			Controls.Add(bar);

			Style(main, Ruby, Color.White);
			main.SetBounds(S(28), S(228), S(220), S(44));
			main.Text = play ? "Play" : "Install and play";
			main.Font = new Font("Segoe UI Semibold", 12f);
			onMain = Start;
			main.Click += delegate { onMain(); };
			Style(close, Card, Ink);
			close.SetBounds(S(372), S(228), S(120), S(44));
			close.Text = "Close";
			close.Click += delegate { Close(); };
			Controls.Add(main);
			Controls.Add(close);
			AcceptButton = main;

			FormClosing += delegate(object s, FormClosingEventArgs e) {
				if (busy && MessageBox.Show(this, "Ruby Wars is still being set up. Stop anyway?", "Ruby Wars", MessageBoxButtons.YesNo,
						MessageBoxIcon.Question) != DialogResult.Yes) {
					e.Cancel = true;
				}
			};
			if (play || Testing) {
				Shown += delegate { Start(); };
			}
			string shot = Environment.GetEnvironmentVariable("RW_SHOT");
			if (!string.IsNullOrEmpty(shot)) {
				// For checking how it looks: a picture of the window, then gone.
				Shown += delegate {
					Application.DoEvents();
					var bmp = new Bitmap(Width, Height);
					DrawToBitmap(bmp, new Rectangle(0, 0, Width, Height));
					bmp.Save(shot);
					Environment.Exit(0);
				};
			}
		}

		static void Style(Button b, Color back, Color fore) {
			b.FlatStyle = FlatStyle.Flat;
			b.FlatAppearance.BorderSize = 0;
			b.BackColor = back;
			b.ForeColor = fore;
			b.Cursor = Cursors.Hand;
		}

		/** A cut ruby: the logo. */
		static void DrawGem(Graphics g, int x, int y, int s) {
			g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
			var top = new[] { new PointF(x + s * 0.2f, y), new PointF(x + s * 0.8f, y), new PointF(x + s, y + s * 0.32f), new PointF(x, y + s * 0.32f) };
			var bottom = new[] { new PointF(x, y + s * 0.32f), new PointF(x + s, y + s * 0.32f), new PointF(x + s * 0.5f, y + s) };
			g.FillPolygon(new SolidBrush(Color.FromArgb(240, 80, 104)), top);
			g.FillPolygon(new SolidBrush(Ruby), bottom);
			g.FillPolygon(new SolidBrush(Color.FromArgb(150, 16, 40)), new[] { new PointF(x + s * 0.5f, y + s * 0.32f), new PointF(x + s, y + s * 0.32f),
				new PointF(x + s * 0.5f, y + s) });
			g.FillPolygon(new SolidBrush(Color.FromArgb(255, 170, 184)), new[] { new PointF(x + s * 0.3f, y + s * 0.06f), new PointF(x + s * 0.5f, y + s * 0.06f),
				new PointF(x + s * 0.42f, y + s * 0.26f) });
		}

		// ----- Talking to the window from the background -----

		void Say(string what, string more) {
			BeginInvoke((Action) delegate {
				status.Text = what;
				if (more != null) {
					detail.Text = more;
				}
			});
		}

		void Progress(int done, int total) {
			BeginInvoke((Action) delegate {
				bar.Maximum = Math.Max(1, total);
				bar.Value = Math.Min(bar.Maximum, Math.Max(0, done));
			});
		}

		void Start() {
			if (busy) {
				return;
			}
			if (!Directory.Exists(mcDir)) {
				status.Text = "Minecraft hasn't been started on this PC yet.";
				detail.Text = "Install the Minecraft Launcher (free in the Microsoft Store), sign in, press Play once, close it, then open this again.";
				main.Text = "Check again";
				if (!Testing) {
					Process.Start("https://www.minecraft.net/download");
				}
				return;
			}
			if (!Testing && LauncherRunning()) {
				status.Text = "Please close the Minecraft Launcher first.";
				detail.Text = "It needs to be closed so it picks up Ruby Wars. Close it, then click the button again.";
				main.Text = "It's closed: carry on";
				return;
			}
			busy = true;
			main.Enabled = false;
			close.Enabled = false;
			Task.Factory.StartNew(Work).ContinueWith(t => BeginInvoke((Action) delegate { Done(t.Exception); }));
		}

		void Done(AggregateException error) {
			busy = false;
			close.Enabled = true;
			main.Enabled = true;
			if (error != null) {
				Exception e = error.InnerException ?? error;
				status.Text = "Something went wrong.";
				detail.Text = e.Message + "  Nothing was broken: try again, and if it keeps happening send a photo of this to whoever runs the server.";
				main.Text = playMode ? "Play anyway" : "Try again";
				if (playMode) {
					onMain = delegate { OpenLauncher(); Close(); };
				}
				if (Testing) {
					Console.Error.WriteLine("FAILED: " + e);
					Environment.Exit(1);
				}
				return;
			}
			bar.Value = bar.Maximum;
			if (Testing) {
				Environment.Exit(0);
			}
			if (playMode) {
				status.Text = "Opening the Minecraft Launcher...";
				detail.Text = "Pick \"Ruby Wars\" (bottom left, next to Play) and press Play.";
				OpenLauncher();
				var t = new System.Windows.Forms.Timer { Interval = 2500 };
				t.Tick += delegate { t.Stop(); Close(); };
				t.Start();
				return;
			}
			status.Text = "All set!";
			detail.Text = "From now on just use the Ruby Wars shortcut on your desktop: it checks for updates every time. "
					+ "In the launcher pick \"Ruby Wars\" (bottom left) and press Play. The server is already in your list.";
			main.Text = "Play now";
			onMain = delegate { OpenLauncher(); Close(); };
		}

		// ----- The work -----

		void Work() {
			Directory.CreateDirectory(gameDir);
			ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
			string versionFile = Path.Combine(gameDir, "rubywars-version.txt");
			string have = File.Exists(versionFile) ? File.ReadAllText(versionFile).Trim() : "";
			Say(playMode ? "Checking for updates..." : "Finding the newest Ruby Wars...", "");
			string tag = LatestTag();
			if (tag == null) {
				if (have.Length > 0) {
					Say("Couldn't reach GitHub: playing the version you have.", "");
					return;
				}
				throw new Exception("Couldn't reach GitHub. Check the internet connection.");
			}
			Dictionary<string, object> deps;
			if (tag == have && !NeedsRepair()) {
				Say("Ruby Wars is up to date.", "");
				deps = null;
			} else {
				deps = SyncPack(tag);
			}
			if (deps != null) {
				string loader = InstallFabric((string) deps["minecraft"], (string) deps["fabric-loader"]);
				SetLauncherProfile(loader);
			} else if (!playMode) {
				// Up to date already: still make sure the launcher entry is there.
				var dep = PackDeps(tag);
				SetLauncherProfile(InstallFabric((string) dep["minecraft"], (string) dep["fabric-loader"]));
			}
			AddServer();
			SetOptions();
			InstallSelf();
			File.WriteAllText(versionFile, tag);
		}

		/** Something the pack needs is missing (a mod deleted by hand, say): sync again even with the same version. */
		bool NeedsRepair() {
			string list = Path.Combine(gameDir, "rubywars-files.txt");
			if (!File.Exists(list)) {
				return true;
			}
			foreach (string f in File.ReadAllLines(list)) {
				if (f.Length > 0 && !File.Exists(Safe(f))) {
					return true;
				}
			}
			return false;
		}

		static string LatestTag() {
			try {
				var request = (HttpWebRequest) WebRequest.Create("https://github.com/" + Repo + "/releases/latest");
				request.AllowAutoRedirect = false;
				request.Method = "HEAD";
				request.UserAgent = "RubyWarsSetup";
				request.Timeout = 10000;
				using (var response = (HttpWebResponse) request.GetResponse()) {
					string location = response.Headers["Location"] ?? "";
					int i = location.IndexOf("/tag/", StringComparison.Ordinal);
					return i < 0 ? null : location.Substring(i + 5);
				}
			} catch (Exception) {
				return null;
			}
		}

		string Safe(string relative) {
			if (relative.Contains("..") || relative.StartsWith("/") || relative.StartsWith("\\") || relative.Contains(":")) {
				throw new Exception("The pack has a file path that isn't allowed: " + relative);
			}
			return Path.Combine(gameDir, relative.Replace('/', '\\'));
		}

		static string Sha1(string path) {
			using (var sha = SHA1.Create())
			using (var stream = File.OpenRead(path)) {
				return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
			}
		}

		static JavaScriptSerializer Json() {
			return new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
		}

		string DownloadPack(string tag) {
			string pack = Path.Combine(Path.GetTempPath(), "rubywars-" + tag + ".mrpack");
			using (var web = new WebClient()) {
				web.Headers.Add("User-Agent", "RubyWarsSetup");
				web.DownloadFile("https://github.com/" + Repo + "/releases/download/" + tag + "/ruby-wars.mrpack", pack);
			}
			return pack;
		}

		Dictionary<string, object> PackDeps(string tag) {
			string pack = DownloadPack(tag);
			try {
				using (var zip = ZipFile.OpenRead(pack))
				using (var reader = new StreamReader(zip.GetEntry("modrinth.index.json").Open())) {
					var index = Json().Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
					return (Dictionary<string, object>) index["dependencies"];
				}
			} finally {
				File.Delete(pack);
			}
		}

		Dictionary<string, object> SyncPack(string tag) {
			Say("Downloading Ruby Wars...", "Only what's new or changed is downloaded.");
			string pack = DownloadPack(tag);
			var keep = new List<string>();
			Dictionary<string, object> deps;
			try {
				using (var zip = ZipFile.OpenRead(pack)) {
					Dictionary<string, object> index;
					using (var reader = new StreamReader(zip.GetEntry("modrinth.index.json").Open())) {
						index = Json().Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
					}
					deps = (Dictionary<string, object>) index["dependencies"];
					var files = ((System.Collections.ArrayList) index["files"]).Cast<Dictionary<string, object>>()
							.Where(f => !(f.ContainsKey("env") && "unsupported".Equals(((Dictionary<string, object>) f["env"])["client"]))).ToList();
					int n = 0;
					foreach (var file in files) {
						string relative = (string) file["path"];
						string target = Safe(relative);
						keep.Add(relative);
						string sha = ((string) ((Dictionary<string, object>) file["hashes"])["sha1"]).ToLowerInvariant();
						Progress(n++, files.Count + 1);
						if (File.Exists(target) && Sha1(target) == sha) {
							continue;
						}
						Say("Getting " + Path.GetFileName(relative) + "...", null);
						Directory.CreateDirectory(Path.GetDirectoryName(target));
						string part = target + ".part";
						using (var web = new WebClient()) {
							web.Headers.Add("User-Agent", "RubyWarsSetup");
							web.DownloadFile((string) ((System.Collections.ArrayList) file["downloads"])[0], part);
						}
						if (Sha1(part) != sha) {
							File.Delete(part);
							throw new Exception("A download didn't arrive whole (" + Path.GetFileName(relative) + "). Try again in a minute.");
						}
						if (File.Exists(target)) {
							File.Delete(target);
						}
						File.Move(part, target);
					}
					foreach (var entry in zip.Entries) {
						if (!entry.FullName.StartsWith("overrides/") || entry.FullName.EndsWith("/")) {
							continue;
						}
						string relative = entry.FullName.Substring(10);
						string target = Safe(relative);
						Directory.CreateDirectory(Path.GetDirectoryName(target));
						entry.ExtractToFile(target, true);
						keep.Add(relative);
					}
					Progress(files.Count + 1, files.Count + 1);
				}
			} finally {
				File.Delete(pack);
			}
			// Mods an older pack had that this one doesn't: gone (two versions of one mod won't start).
			string list = Path.Combine(gameDir, "rubywars-files.txt");
			if (File.Exists(list)) {
				foreach (string old in File.ReadAllLines(list)) {
					if (old.Length > 0 && !keep.Contains(old)) {
						try {
							File.Delete(Safe(old));
						} catch (Exception) {
						}
					}
				}
			}
			File.WriteAllLines(list, keep);
			return deps;
		}

		/** Fabric, as a version the launcher can start (the launcher downloads the rest itself). */
		string InstallFabric(string minecraft, string loader) {
			string id = "fabric-loader-" + loader + "-" + minecraft;
			string dir = Path.Combine(mcDir, "versions", id);
			string json = Path.Combine(dir, id + ".json");
			if (!File.Exists(json)) {
				Say("Adding Fabric...", null);
				Directory.CreateDirectory(dir);
				using (var web = new WebClient()) {
					web.Headers.Add("User-Agent", "RubyWarsSetup");
					web.DownloadFile("https://meta.fabricmc.net/v2/versions/loader/" + minecraft + "/" + loader + "/profile/json", json);
				}
			}
			return id;
		}

		/** The "Ruby Wars" entry in the launcher's list. */
		void SetLauncherProfile(string versionId) {
			Say("Adding Ruby Wars to the Minecraft Launcher...", null);
			int memory = 3;
			try {
				double gb = new Microsoft.VisualBasic.Devices.ComputerInfo().TotalPhysicalMemory / (1024.0 * 1024 * 1024);
				memory = gb >= 15 ? 6 : gb >= 11 ? 4 : 3;
			} catch (Exception) {
			}
			var files = new[] { "launcher_profiles.json", "launcher_profiles_microsoft_store.json" }.Select(f => Path.Combine(mcDir, f))
					.Where(File.Exists).ToList();
			if (files.Count == 0) {
				string path = Path.Combine(mcDir, "launcher_profiles.json");
				File.WriteAllText(path, "{\"profiles\":{},\"version\":3}");
				files.Add(path);
			}
			string now = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
			foreach (string path in files) {
				var data = Json().Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
				if (!File.Exists(path + ".before-rubywars")) {
					File.Copy(path, path + ".before-rubywars");
				}
				object p;
				var profiles = data.TryGetValue("profiles", out p) && p is Dictionary<string, object> ? (Dictionary<string, object>) p : new Dictionary<string, object>();
				data["profiles"] = profiles;
				var entry = new Dictionary<string, object> {
					{ "name", "Ruby Wars" }, { "type", "custom" }, { "created", now }, { "lastUsed", now }, { "icon", "Redstone_Block" },
					{ "lastVersionId", versionId }, { "gameDir", gameDir }, { "javaArgs", "-Xmx" + memory + "G -XX:+UseG1GC" } };
				object had;
				if (profiles.TryGetValue("ruby-wars", out had) && had is Dictionary<string, object>) {
					var old = (Dictionary<string, object>) had;
					// Keep when it was made, and any memory setting the player changed.
					if (old.ContainsKey("created")) {
						entry["created"] = old["created"];
					}
					if (old.ContainsKey("javaArgs")) {
						entry["javaArgs"] = old["javaArgs"];
					}
				}
				profiles["ruby-wars"] = entry;
				File.WriteAllText(path, Json().Serialize(data), new UTF8Encoding(false));
			}
		}

		/** The server in the multiplayer list: a new list with just it, or added to the top of the player's own list. */
		void AddServer() {
			string addressFile = Path.Combine(gameDir, "rubywars-server.txt");
			if (!File.Exists(addressFile)) {
				return;
			}
			string ip = File.ReadAllLines(addressFile).FirstOrDefault();
			if (string.IsNullOrWhiteSpace(ip)) {
				return;
			}
			ip = ip.Trim();
			string path = Path.Combine(gameDir, "servers.dat");
			var entry = new MemoryStream();
			NbtString(entry, 8, "name");
			NbtText(entry, "Ruby Wars");
			NbtString(entry, 8, "ip");
			NbtText(entry, ip);
			entry.WriteByte(0);
			byte[] server = entry.ToArray();
			if (File.Exists(path)) {
				byte[] old = File.ReadAllBytes(path);
				if (IndexOf(old, Encoding.UTF8.GetBytes(ip)) >= 0) {
					return;
				}
				// servers.dat is { servers: [ {...}, ... ] }: put ours first in the list.
				byte[] head = { 9, 0, 7, (byte) 's', (byte) 'e', (byte) 'r', (byte) 'v', (byte) 'e', (byte) 'r', (byte) 's', 10 };
				int at = IndexOf(old, head);
				if (at < 0) {
					return;
				}
				int countAt = at + head.Length;
				int count = (old[countAt] << 24) | (old[countAt + 1] << 16) | (old[countAt + 2] << 8) | old[countAt + 3];
				var merged = new MemoryStream();
				merged.Write(old, 0, countAt);
				count++;
				merged.Write(new[] { (byte) (count >> 24), (byte) (count >> 16), (byte) (count >> 8), (byte) count }, 0, 4);
				merged.Write(server, 0, server.Length);
				merged.Write(old, countAt + 4, old.Length - countAt - 4);
				File.WriteAllBytes(path, merged.ToArray());
				return;
			}
			var file = new MemoryStream();
			file.WriteByte(10);
			NbtText(file, "");
			NbtString(file, 9, "servers");
			file.WriteByte(10);
			file.Write(new byte[] { 0, 0, 0, 1 }, 0, 4);
			file.Write(server, 0, server.Length);
			file.WriteByte(0);
			File.WriteAllBytes(path, file.ToArray());
		}

		static void NbtString(Stream s, byte type, string name) {
			s.WriteByte(type);
			NbtText(s, name);
		}

		static void NbtText(Stream s, string text) {
			byte[] b = Encoding.UTF8.GetBytes(text);
			s.WriteByte((byte) (b.Length >> 8));
			s.WriteByte((byte) b.Length);
			s.Write(b, 0, b.Length);
		}

		static int IndexOf(byte[] hay, byte[] needle) {
			for (int i = 0; i + needle.Length <= hay.Length; i++) {
				int j = 0;
				while (j < needle.Length && hay[i + j] == needle[j]) {
					j++;
				}
				if (j == needle.Length) {
					return i;
				}
			}
			return -1;
		}

		/** Starting settings, the first time only: the player's own if they have some, friendly ones if not. */
		void SetOptions() {
			string target = Path.Combine(gameDir, "options.txt");
			if (File.Exists(target)) {
				return;
			}
			string own = Path.Combine(mcDir, "options.txt");
			var lines = File.Exists(own) ? File.ReadAllLines(own).ToList()
					: new List<string> { "gamma:1.0", "soundCategory_music:0.5", "autoJump:true", "renderDistance:10", "guiScale:0", "narrator:0" };
			var always = new Dictionary<string, string> { { "tutorialStep", "none" }, { "onboardAccessibility", "false" },
				{ "skipMultiplayerWarning", "true" }, { "joinedFirstServer", "true" } };
			foreach (var pair in always) {
				int i = lines.FindIndex(l => l.StartsWith(pair.Key + ":"));
				if (i >= 0) {
					lines[i] = pair.Key + ":" + pair.Value;
				} else {
					lines.Add(pair.Key + ":" + pair.Value);
				}
			}
			File.WriteAllLines(target, lines);
		}

		/** This program in its own folder, with "Ruby Wars" shortcuts on the desktop and in the Start menu. */
		void InstallSelf() {
			string exe = Path.Combine(installDir, "Ruby Wars.exe");
			string me = Application.ExecutablePath;
			Directory.CreateDirectory(installDir);
			if (!string.Equals(Path.GetFullPath(me), Path.GetFullPath(exe), StringComparison.OrdinalIgnoreCase)) {
				try {
					File.Copy(me, exe, true);
				} catch (IOException) {
					// It's running from there already (an older copy): keep that one.
				}
			}
			string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
			string menu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Ruby Wars.lnk");
			if (Testing) {
				return;
			}
			Shortcut(Path.Combine(desktop, "Ruby Wars.lnk"), exe);
			Shortcut(menu, exe);
		}

		static void Shortcut(string link, string exe) {
			Type shellType = Type.GetTypeFromProgID("WScript.Shell");
			object shell = Activator.CreateInstance(shellType);
			object sc = shellType.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod, null, shell, new object[] { link });
			Type t = sc.GetType();
			t.InvokeMember("TargetPath", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { exe });
			t.InvokeMember("Arguments", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { "--play" });
			t.InvokeMember("WorkingDirectory", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { Path.GetDirectoryName(exe) });
			t.InvokeMember("IconLocation", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { exe + ",0" });
			t.InvokeMember("Description", System.Reflection.BindingFlags.SetProperty, null, sc, new object[] { "Play Ruby Wars (updates itself first)" });
			t.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, sc, null);
		}

		// ----- The launcher -----

		static bool LauncherRunning() {
			return Process.GetProcessesByName("MinecraftLauncher").Length > 0 || Process.GetProcessesByName("Minecraft").Length > 0;
		}

		void OpenLauncher() {
			string x86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
			string x64 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
			foreach (string path in new[] { Path.Combine(x86, "Minecraft Launcher", "MinecraftLauncher.exe"), Path.Combine(x64, "Minecraft Launcher",
					"MinecraftLauncher.exe"), @"C:\XboxGames\Minecraft Launcher\Content\Minecraft.exe" }) {
				if (File.Exists(path)) {
					Process.Start(path);
					return;
				}
			}
			try {
				// The Microsoft Store launcher.
				Process.Start("explorer.exe", @"shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft");
			} catch (Exception) {
				MessageBox.Show(this, "Couldn't find the Minecraft Launcher: open it yourself and pick \"Ruby Wars\".", "Ruby Wars");
			}
		}
	}
}
