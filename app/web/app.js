const list = document.getElementById("list");
const empty = document.getElementById("empty");
const error = document.getElementById("error");
const progress = document.getElementById("progress");
const rescan = document.getElementById("rescan");
const chosen = document.getElementById("chosen");
const network = document.getElementById("network");
const installBtn = document.getElementById("install");
const installLog = document.getElementById("install-log");

let selectedIp = null;
let lastKey = "";

function escapeText(value) {
  return String(value == null ? "" : value);
}

function deviceRow(device) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "tv" + (device.ip === selectedIp ? " selected" : "");
  row.dataset.ip = device.ip;

  const text = document.createElement("div");
  const name = document.createElement("div");
  name.className = "name";
  name.textContent = escapeText(device.name || device.ip);
  const meta = document.createElement("div");
  meta.className = "meta";
  const bits = [device.ip];
  if (device.android) bits.push("Android " + device.android);
  if (device.manufacturer) bits.push(device.manufacturer);
  if (device.note) bits.push(device.note);
  meta.textContent = bits.join(" · ");
  text.append(name, meta);

  const badges = document.createElement("div");
  badges.className = "badges";
  const adb = document.createElement("span");
  adb.className = "badge " + (device.adb ? "on" : "off");
  adb.textContent = device.adb ? "ADB prêt" : "ADB fermé";
  badges.append(adb);
  if (device.ip === selectedIp) {
    const check = document.createElement("span");
    check.className = "badge on";
    check.textContent = "Choisie";
    badges.append(check);
  }

  row.append(text, badges);
  row.addEventListener("click", () => choose(device));
  return row;
}

async function choose(device) {
  const answer = await fetch("/api/select", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ip: device.ip, name: device.name || device.ip }),
  }).then((response) => response.json()).catch(() => null);
  if (answer && answer.ok) {
    selectedIp = device.ip;
    lastKey = "";
    renderChosen(device);
    await refresh();
  }
}

function renderChosen(device) {
  chosen.replaceChildren();
  if (!device) {
    const none = document.createElement("p");
    none.className = "none";
    none.textContent = "Aucune télé choisie. Clique sur une télé de la liste.";
    chosen.append(none);
    return;
  }
  const big = document.createElement("div");
  big.className = "big";
  big.textContent = escapeText(device.name || device.ip);
  const ip = document.createElement("div");
  ip.className = "ip";
  ip.textContent = device.ip;
  chosen.append(big, ip);
  if (device.model && device.model.toLowerCase() !== (device.name || "").toLowerCase()) {
    const model = document.createElement("div");
    model.className = "detail";
    model.textContent = device.model;
    chosen.append(model);
  }
  const line = document.createElement("div");
  line.className = "detail";
  const bits = [];
  if (device.manufacturer) bits.push(device.manufacturer);
  if (device.android) bits.push("Android " + device.android);
  if (bits.length) {
    line.textContent = bits.join(" · ");
    chosen.append(line);
  }

  const badges = document.createElement("div");
  badges.className = "badges chosen-badges";
  const adb = document.createElement("span");
  adb.className = "badge " + (device.adb ? "on" : "off");
  adb.textContent = device.adb ? "ADB prêt" : "ADB fermé";
  badges.append(adb);
  chosen.append(badges);

  if (device.note) {
    const note = document.createElement("p");
    note.className = "note";
    note.textContent = device.note;
    chosen.append(note);
  }
}

function renderInstall(install) {
  const lines = install.lines || [];
  installLog.hidden = lines.length === 0;
  installLog.replaceChildren(...lines.map((line) => {
    const row = document.createElement("div");
    row.className = "line " + (line.state || "ok");
    const dot = document.createElement("span");
    dot.className = "dot";
    const text = document.createElement("span");
    text.textContent = line.text;
    row.append(dot, text);
    return row;
  }));
  installLog.scrollTop = installLog.scrollHeight;
  installBtn.textContent = install.running
    ? "Installation…"
    : install.ok === true ? "Réinstaller le thème" : "Installer le thème";
  return !!install.running;
}

function render(state) {
  const scan = state.scan || {};
  const devices = scan.devices || [];
  selectedIp = state.selected ? state.selected.ip : null;
  const known = devices.find((device) => device.ip === selectedIp);
  const installing = renderInstall(state.install || {});
  installBtn.disabled = installing || !selectedIp || (known && !known.adb);

  progress.textContent = scan.scanning
    ? `${scan.done} / ${scan.total} adresses`
    : devices.length
      ? `${devices.length} télé${devices.length > 1 ? "s" : ""}`
      : "terminé";
  progress.classList.toggle("idle", !scan.scanning);
  rescan.disabled = !!scan.scanning;
  network.textContent = "Réseau " + (scan.network || "—");

  error.hidden = !scan.error;
  error.textContent = scan.error || "";

  empty.hidden = scan.scanning || devices.length > 0;

  const key = devices.map((device) => device.ip + device.name + device.adb).join("|") + selectedIp;
  if (key !== lastKey) {
    lastKey = key;
    list.replaceChildren(...devices.map(deviceRow));
  }
  const current = devices.find((device) => device.ip === selectedIp)
    || (state.selected ? { ip: state.selected.ip, name: state.selected.name } : null);
  renderChosen(current);
}

async function refresh() {
  try {
    const state = await fetch("/api/state").then((response) => response.json());
    render(state);
    return state;
  } catch (problem) {
    error.hidden = false;
    error.textContent = "Le serveur local ne répond plus.";
    return null;
  }
}

async function scan() {
  await fetch("/api/scan", { method: "POST" });
  refresh();
}

installBtn.addEventListener("click", async () => {
  if (!selectedIp) return;
  installBtn.disabled = true;
  await fetch("/api/install", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ip: selectedIp }),
  });
  refresh();
});

rescan.addEventListener("click", scan);
document.getElementById("quit").addEventListener("click", () => {
  fetch("/api/quit", { method: "POST" });
  window.close();
});

refresh();
setInterval(async () => {
  const state = await refresh();
  if (!state || !state.scan || !state.scan.scanning) {
    // Nothing to poll for; the next scan restarts it from the button or the next start.
  }
}, 900);
