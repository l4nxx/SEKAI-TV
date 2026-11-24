// Musique TV — la fenêtre : on glisse des fichiers, on les envoie à l'app de la TV, on voit ce
// qu'elle contient déjà. Tout passe par le serveur local (127.0.0.1), rien par le réseau.

const AUDIO = [".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".mp4"];

const state = {
  host: "",
  files: [],            // { name, size, status: 'uploading' | 'ready' | 'error', error }
  tv: { ok: false, model: "", tracks: [], error: "" },
};

const $ = (id) => document.getElementById(id);

// --- small helpers ---------------------------------------------------------------------------

async function api(path, body) {
  const options = body === undefined ? {} : {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
  try {
    const response = await fetch(path, options);
    return await response.json();
  } catch (error) {
    return { ok: false, error: "serveur local injoignable" };
  }
}

function size(bytes) {
  if (!bytes && bytes !== 0) return "";
  return bytes >= 1048576
    ? `${(bytes / 1048576).toFixed(1).replace(".", ",")} Mo`
    : `${Math.max(1, Math.round(bytes / 1024))} Ko`;
}

function isAudio(name) {
  const lower = name.toLowerCase();
  return AUDIO.some((extension) => lower.endsWith(extension));
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// --- the selection ---------------------------------------------------------------------------

function readyFiles() {
  return state.files.filter((file) => file.status === "ready");
}

function renderSelection() {
  const list = $("selList");
  list.replaceChildren();

  if (!state.files.length) {
    list.append(el("div", "empty", "Aucun titre choisi.\nGlisse des fichiers dans la zone ci-dessus."));
  }

  state.files.forEach((file, index) => {
    const row = el("div", `row${file.status === "error" ? " error" : ""}`);
    row.append(el("span", "index", String(index + 1).padStart(2, "0")));

    const meta = el("div", "meta");
    const name = el("div", "name", file.name);
    name.title = file.name;
    const sub = el("div", "sub",
      file.status === "uploading" ? "copie en cours…"
        : file.status === "error" ? (file.error || "échec de la copie")
          : size(file.size));
    meta.append(name, sub);
    row.append(meta);

    const remove = el("button", "remove", "×");
    remove.title = "Retirer de la sélection";
    remove.addEventListener("click", () => {
      state.files.splice(index, 1);
      renderSelection();
    });
    row.append(remove);
    list.append(row);
  });

  const ready = readyFiles().length;
  $("selCount").textContent = state.files.length
    ? `${state.files.length} fichier${state.files.length > 1 ? "s" : ""}`
    : "";

  const uploading = state.files.some((file) => file.status === "uploading");
  $("send").disabled = !ready || uploading;
  $("send").textContent = ready > 1 ? `Envoyer les ${ready} sur la TV` : "Envoyer sur la TV";

  const hint = $("selHint");
  if (uploading) {
    hint.textContent = "Copie en cours…";
    hint.className = "muted";
  } else if (ready) {
    hint.textContent = `${ready} prêt${ready > 1 ? "s" : ""} à partir`;
    hint.className = "muted";
  } else {
    hint.textContent = "Rien de choisi pour l'instant";
    hint.className = "muted";
  }
}

async function addFiles(fileList) {
  // Take the files out of the input's list right away: the caller empties that input just after
  // (otherwise choosing the same file twice would do nothing), and emptying it empties the list we
  // would still be walking — only the first file ever made it through.
  const files = Array.from(fileList);
  for (const file of files) {
    if (!isAudio(file.name)) continue;
    if (state.files.some((known) => known.name === file.name)) continue;

    const entry = { name: file.name, size: file.size, status: "uploading" };
    state.files.push(entry);
    renderSelection();

    try {
      const response = await fetch(`/api/upload?name=${encodeURIComponent(file.name)}`,
        { method: "POST", body: file });
      const answer = await response.json();
      if (answer.ok) {
        entry.name = answer.file.name;
        entry.size = answer.file.size;
        entry.status = "ready";
      } else {
        entry.status = "error";
        entry.error = answer.error || "refusé";
      }
    } catch (error) {
      entry.status = "error";
      entry.error = "copie impossible";
    }
    renderSelection();
  }
}

// --- the TV library --------------------------------------------------------------------------

function renderTv() {
  const list = $("tvList");
  list.replaceChildren();

  const dot = $("tvDot");
  const name = $("tvName");
  if (state.tv.ok) {
    dot.className = "dot online";
    name.textContent = state.tv.model || "TV connectée";
    name.title = state.tv.model || "";
  } else {
    dot.className = "dot offline";
    name.textContent = "TV injoignable";
    name.title = state.tv.error || "";
  }

  const tracks = state.tv.tracks || [];
  $("tvCount").textContent = tracks.length
    ? `${tracks.length} titre${tracks.length > 1 ? "s" : ""}`
    : "";

  if (!tracks.length) {
    list.append(el("div", "empty",
      state.tv.ok ? "La bibliothèque de la TV est vide." : "Branche la TV pour voir sa bibliothèque."));
    return;
  }

  tracks.forEach((track, index) => {
    const row = el("div", "row playing");
    row.append(el("span", "index", String(index + 1).padStart(2, "0")));
    const meta = el("div", "meta");
    const title = el("div", "name", track.name);
    title.title = track.name;
    meta.append(title, el("div", "sub", size(track.size)));
    row.append(meta);

    const remove = el("button", "remove", "×");
    remove.title = "Retirer de la TV";
    remove.addEventListener("click", () => deleteTrack(track.name));
    row.append(remove);
    list.append(row);
  });
}

async function deleteTrack(name) {
  log("info", `suppression de ${name} sur la TV…`);
  const answer = await api("/api/delete", { name });
  if (answer.tv) state.tv = answer.tv;
  else await refresh();
  renderTv();
  log(answer.ok ? "ok" : "error", answer.ok ? "    retiré" : `    échec : ${answer.error || "refusé"}`);
}

// --- sending ---------------------------------------------------------------------------------

let pollTimer = null;

async function send() {
  const names = readyFiles().map((file) => file.name);
  if (!names.length) return;

  $("send").disabled = true;
  $("bar").style.width = "0%";
  $("log").replaceChildren();

  const answer = await api("/api/send", { names });
  if (!answer.ok) {
    log("error", answer.error || "envoi refusé");
    $("send").disabled = false;
    return;
  }
  poll();
}

async function poll() {
  clearTimeout(pollTimer);
  const job = await api("/api/job");
  renderJob(job);
  if (job.running || !job.finished) {
    pollTimer = setTimeout(poll, 350);
  } else {
    await refresh();
    $("send").disabled = readyFiles().length === 0;
  }
}

function renderJob(job) {
  const ratio = job.total ? Math.min(100, Math.round((job.done / job.total) * 100)) : 0;
  if (job.running && job.current) {
    const inside = job.total > 1 ? ` (${job.done + 1}/${job.total})` : "";
    $("bar").style.width = `${job.total > 1 ? Math.round((job.done / job.total) * 100) : 0}%`;
    // The bar creeps along inside the file being sent so a long one does not look stuck.
    $("bar").style.width = `${Math.min(99, ratio + (job.total > 1 ? 100 / job.total / 2 : 8))}%`;
    $("bar").title = job.current + inside;
  } else {
    $("bar").style.width = `${ratio}%`;
  }

  const box = $("log");
  box.replaceChildren();
  for (const line of job.log || []) {
    box.append(el("div", line.kind, line.text));
  }
  box.scrollTop = box.scrollHeight;
}

function log(kind, text) {
  const box = $("log");
  box.append(el("div", kind, text));
  box.scrollTop = box.scrollHeight;
}

// --- startup ---------------------------------------------------------------------------------

async function refresh() {
  const answer = await api("/api/state");
  if (answer.host) {
    state.host = answer.host;
    if (document.activeElement !== $("host")) $("host").value = answer.host;
  }
  if (answer.tv) state.tv = answer.tv;
  renderTv();
}

async function testTv() {
  const host = $("host").value.trim();
  if (host) await api("/api/host", { host });
  $("tvName").textContent = "Connexion…";
  $("tvDot").className = "dot";
  const answer = await api("/api/test");
  if (answer.tv) state.tv = answer.tv;
  renderTv();
}

function wire() {
  const dropzone = $("dropzone");
  const picker = $("picker");

  dropzone.addEventListener("click", () => picker.click());
  picker.addEventListener("change", () => {
    addFiles(picker.files);
    picker.value = "";
  });

  dropzone.addEventListener("dragover", (event) => {
    event.preventDefault();
    dropzone.classList.add("over");
  });
  dropzone.addEventListener("dragleave", () => dropzone.classList.remove("over"));
  dropzone.addEventListener("drop", (event) => {
    event.preventDefault();
    dropzone.classList.remove("over");
    addFiles(event.dataTransfer.files);
  });
  // Dropping anywhere else must not make the window navigate to the file.
  document.addEventListener("dragover", (event) => event.preventDefault());
  document.addEventListener("drop", (event) => event.preventDefault());

  $("clear").addEventListener("click", () => {
    state.files = state.files.filter((file) => file.status === "uploading");
    renderSelection();
  });
  $("send").addEventListener("click", send);
  $("test").addEventListener("click", testTv);
  $("host").addEventListener("change", async () => {
    const host = $("host").value.trim();
    if (host) await api("/api/host", { host });
  });
  $("host").addEventListener("keydown", (event) => {
    if (event.key === "Enter") testTv();
  });
  $("quit").addEventListener("click", async () => {
    await api("/api/quit", {});
    window.close();
  });
}

wire();
renderSelection();
refresh();
setInterval(refresh, 15000);
