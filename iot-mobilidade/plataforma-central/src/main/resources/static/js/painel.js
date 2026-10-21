// Painel de mobilidade · Plataforma IoT Integrada
// Mapa (Leaflet), gráficos (Chart.js), indicadores, alarmes e controle do nó de borda. Atualiza a cada 2 segundos.
'use strict';

// Endereço do nó de borda de demonstração (para ver a fila local ao vivo e simular a queda do link).
// Para mudar, defina window.EDGE_URL antes de carregar este script.
const EDGE_URL = window.EDGE_URL || (location.protocol + '//' + location.hostname + ':8081');
const LIMITE_CONGESTIONAMENTO = 15; // km/h, igual à propriedade plataforma.nivel.congestionado-max
const COR = { FLUIDO: '#2e9e5b', MODERADO: '#f2a516', CONGESTIONADO: '#d32f2f', SEM_DADOS: '#9aa5b1' };
const ROTULO = { FLUIDO: 'Fluido', MODERADO: 'Moderado', CONGESTIONADO: 'Congestionado', SEM_DADOS: 'Sem dados' };
const TIPO = { 'contador-veiculos': 'Contador', semaforo: 'Semáforo' };

const $ = (id) => document.getElementById(id);
const esc = (t) => String(t ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const num = (v, d = 1) => (v === null || v === undefined ? '–' : Number(v).toFixed(d));
function ha(iso) {
  if (!iso) { return '–'; }
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) { return 'há ' + s + ' s'; }
  if (s < 3600) { return 'há ' + Math.floor(s / 60) + ' min'; }
  return 'há ' + Math.floor(s / 3600) + ' h ' + Math.floor((s % 3600) / 60) + ' min';
}

// ---------------------------------------------------------------- estado
let areas = [];
let alarmes = [];
let nosCentral = [];
let dispositivos = [];
let borda = null; // estado local do nó, lido direto dele
let areaSelecionada = null;
let grafico = null;
let spark = null;
let primeiraCarga = true;
const anterior = { alarmes: null, nos: null, dispositivos: null, link: null };
const marcadores = {};
const htmlMarcador = {};

const nomeArea = (slug) => (areas.find((a) => a.slug === slug) || {}).nome || slug;

async function api(url) {
  const r = await fetch(url, { credentials: 'same-origin' });
  if (r.redirected || r.status === 401) { window.location.href = '/login'; throw new Error('sessão expirada'); }
  if (!r.ok) { throw new Error(url + ' -> ' + r.status); }
  return r.json();
}

// ---------------------------------------------------------------- avisos e atividade
function toast(tipo, titulo, texto) {
  const el = document.createElement('div');
  el.className = 'toast ' + tipo;
  el.innerHTML = '<b>' + esc(titulo) + '</b><span>' + esc(texto) + '</span>';
  $('toasts').appendChild(el);
  setTimeout(() => el.classList.add('sai'), 5500);
  setTimeout(() => el.remove(), 6300);
}

const atividades = [];
function registrar(tipo, texto, avisar) {
  atividades.unshift({ tipo, texto, quando: new Date() });
  if (atividades.length > 25) { atividades.pop(); }
  $('feed').innerHTML = atividades.map((a) => '<li class="' + a.tipo + '"><time>' + a.quando.toLocaleTimeString('pt-BR') + '</time><i></i><span>'
    + esc(a.texto) + '</span></li>').join('');
  if (avisar) { toast(tipo, avisar, texto); }
}

// ---------------------------------------------------------------- mapa
const mapa = L.map('mapa').setView([-16.318, -48.95], 13);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' }).addTo(mapa);

function htmlPino(a) {
  const classes = ['pino', a.nivel];
  if (a.via_borda) { classes.push('borda'); }
  if (a.slug === areaSelecionada) { classes.push('sel'); }
  return '<div class="' + classes.join(' ') + '"><span>' + (a.velocidade === null || a.velocidade === undefined ? '–' : Math.round(a.velocidade)) + '</span></div>';
}

function textoTooltip(a) {
  return '<div class="tip"><b>' + esc(a.nome) + '</b><br><span class="nivel" style="color:' + COR[a.nivel] + '">' + ROTULO[a.nivel] + '</span><br>'
    + 'Velocidade: <b>' + num(a.velocidade) + '</b> km/h<br>Fluxo: <b>' + num(a.fluxo, 0) + '</b> veíc/min<br>Ocupação: <b>' + num(a.ocupacao, 0) + '</b>%'
    + (a.via_borda ? '<br>Atendido por nó de borda' : '') + (a.alarmes ? '<br><b style="color:#d32f2f">' + a.alarmes + ' alarme(s) aberto(s)</b>' : '') + '</div>';
}

async function carregarAreas() {
  areas = await api('/api/v1/areas/estado');
  const pontos = [];
  areas.forEach((a) => {
    if (a.lat === null || a.lon === null) { return; }
    pontos.push([a.lat, a.lon]);
    const html = htmlPino(a);
    const icone = () => L.divIcon({ className: 'pino-wrap', html, iconSize: [40, 40], iconAnchor: [20, 20] });
    if (!marcadores[a.slug]) {
      marcadores[a.slug] = L.marker([a.lat, a.lon], { icon: icone(), riseOnHover: true }).addTo(mapa).on('click', () => selecionar(a.slug));
      htmlMarcador[a.slug] = html;
    } else if (htmlMarcador[a.slug] !== html) {
      marcadores[a.slug].setIcon(icone());
      htmlMarcador[a.slug] = html;
    }
    marcadores[a.slug].setZIndexOffset(a.nivel === 'CONGESTIONADO' ? 1000 : 0);
    const tip = marcadores[a.slug].getTooltip();
    if (tip) { tip.setContent(textoTooltip(a)); } else { marcadores[a.slug].bindTooltip(textoTooltip(a), { direction: 'top', offset: [0, -20] }); }
  });
  if (primeiraCarga && pontos.length) { mapa.fitBounds(pontos, { padding: [50, 50], maxZoom: 15 }); }

  const cont = { FLUIDO: 0, MODERADO: 0, CONGESTIONADO: 0, SEM_DADOS: 0 };
  areas.forEach((a) => { cont[a.nivel] += 1; });
  $('kpi-cruz').textContent = areas.length;
  $('kpi-cruz-sub').innerHTML = ['FLUIDO', 'MODERADO', 'CONGESTIONADO', 'SEM_DADOS'].map((n) =>
    '<span title="' + ROTULO[n] + '"><i style="background:' + COR[n] + '"></i><b>' + cont[n] + '</b></span>').join('');

  if (!areaSelecionada && areas.length) {
    const alvo = areas.find((a) => a.nivel === 'CONGESTIONADO') || areas.find((a) => a.nivel !== 'SEM_DADOS') || areas[0];
    selecionar(alvo.slug);
  } else {
    atualizarChips();
  }
}

function selecionar(slug) {
  areaSelecionada = slug;
  $('area-selecionada').textContent = nomeArea(slug);
  Object.keys(marcadores).forEach((s) => { htmlMarcador[s] = null; }); // força redesenhar o anel de seleção
  atualizarChips();
  carregarGrafico().catch((e) => console.warn(e.message));
  carregarAreas().catch(() => { });
}

function atualizarChips() {
  const a = areas.find((x) => x.slug === areaSelecionada);
  if (!a) { return; }
  $('area-sub').textContent = a.via_borda ? 'atendido pelo nó de borda' : 'conexão direta com a central';
  $('area-chips').innerHTML = '<div class="chip ' + a.nivel + '">Situação<b>' + ROTULO[a.nivel] + '</b></div>'
    + '<div class="chip">Velocidade média<b>' + num(a.velocidade) + ' km/h</b></div>'
    + '<div class="chip">Fluxo<b>' + num(a.fluxo, 0) + ' veíc/min</b></div>'
    + '<div class="chip">Ocupação da via<b>' + num(a.ocupacao, 0) + '%</b></div>'
    + '<div class="chip">Último dado<b>' + ha(a.ts) + '</b></div>';
}

// ---------------------------------------------------------------- alarmes
async function carregarAlarmes() {
  const pag = await api('/api/v1/alarmes?estado=ABERTOS&size=50');
  alarmes = pag.itens;
  const total = pag.total;
  const selo = $('qtd-alarmes');
  selo.textContent = total;
  selo.className = 'selo' + (total === 0 ? ' zero' : '');

  // eventos: abertos, reconhecidos e resolvidos (some da lista de abertos)
  if (anterior.alarmes) {
    alarmes.forEach((a) => {
      const antes = anterior.alarmes[a.id];
      if (!antes) {
        registrar(a.severidade === 'CRITICA' || a.severidade === 'ALTA' ? 'erro' : 'alerta',
          a.regra_nome + ' em ' + nomeArea(a.area_slug), 'Novo alarme ' + a.severidade);
      } else if (antes !== a.estado && a.estado === 'RECONHECIDO') {
        registrar('info', 'Alarme reconhecido: ' + a.regra_nome + ' em ' + nomeArea(a.area_slug));
      }
    });
    Object.keys(anterior.alarmes).forEach((id) => {
      if (!alarmes.some((a) => String(a.id) === id)) {
        registrar('ok', 'Alarme resolvido automaticamente (id ' + id + ')', 'Alarme resolvido');
      }
    });
  }
  anterior.alarmes = {};
  alarmes.forEach((a) => { anterior.alarmes[a.id] = a.estado; });

  const crit = alarmes.filter((a) => a.severidade === 'CRITICA').length;
  const altos = alarmes.filter((a) => a.severidade === 'ALTA').length;
  $('kpi-alarmes').textContent = total;
  $('kpi-alarmes-card').className = 'kpi ' + (total === 0 ? 'verde' : 'vermelho');
  $('kpi-alarmes-sub').innerHTML = total === 0 ? 'nenhum alarme aberto' : '<b>' + crit + '</b> crítico(s) · <b>' + altos + '</b> alto(s)';

  $('lista-alarmes').innerHTML = alarmes.map((a) => '<div class="alarme ' + esc(a.severidade) + ' ' + esc(a.estado) + '">'
    + '<div class="alarme-topo"><span class="tag ' + esc(a.severidade) + '">' + esc(a.severidade) + '</span><b>' + esc(a.regra_nome) + '</b>'
    + '<span class="tag ' + esc(a.estado) + '" style="margin-left:auto">' + esc(a.estado) + '</span></div>'
    + '<div class="alarme-meta"><span>' + esc(nomeArea(a.area_slug)) + ' · ' + ha(a.aberto_em) + '</span>'
    + (a.estado === 'ATIVO' ? '<button class="pequeno" onclick="reconhecer(' + a.id + ')">Reconhecer</button>' : '<span>reconhecido por ' + esc(a.reconhecido_por) + '</span>') + '</div></div>').join('')
    || '<div class="vazio">Nenhum alarme aberto</div>';
}

async function reconhecer(id) {
  const r = await fetch('/api/v1/alarmes/' + id + '/reconhecer', { method: 'POST', credentials: 'same-origin' });
  if (!r.ok) { alert('Não foi possível reconhecer o alarme (' + r.status + ')'); }
  carregarAlarmes();
}

// ---------------------------------------------------------------- nó de borda
async function carregarNos() {
  nosCentral = await api('/api/v1/nos-borda');
  if (anterior.nos) {
    nosCentral.forEach((n) => {
      const antes = anterior.nos[n.id];
      if (antes && antes !== n.situacao) {
        if (n.situacao === 'SEM_SINCRONIZAR') { registrar('erro', n.nome + ' sem sincronizar com a central', 'Nó de borda'); }
        else { registrar('ok', n.nome + ' voltou a sincronizar', 'Nó de borda'); }
      }
    });
  }
  anterior.nos = {};
  nosCentral.forEach((n) => { anterior.nos[n.id] = n.situacao; });
  desenharBorda();
}

async function carregarBorda() {
  try {
    const r = await fetch(EDGE_URL + '/api/v1/borda/status');
    borda = r.ok ? await r.json() : null;
  } catch (e) {
    borda = null;
  }
  if (borda && anterior.link !== null && anterior.link !== borda.linkLigado) {
    registrar(borda.linkLigado ? 'ok' : 'erro', borda.linkLigado ? 'Link do nó de borda restaurado' : 'Link do nó de borda com a central DESLIGADO');
  }
  anterior.link = borda ? borda.linkLigado : null;
  desenharBorda();
}

function desenharBorda() {
  const n = nosCentral[0];
  const alvo = $('painel-borda');
  if (!n) { alvo.innerHTML = '<div class="vazio">Nenhum nó de borda cadastrado</div>'; return; }
  const linkTxt = borda ? (borda.linkLigado ? 'LIGADO' : 'DESLIGADO') : 'INACESSÍVEL';
  alvo.innerHTML = '<div class="borda-topo"><b>' + esc(n.nome) + '</b>'
    + '<span class="pill ' + esc(n.situacao) + '"><i></i>' + (n.situacao === 'SINCRONIZADO' ? 'Sincronizado' : 'Sem sincronizar') + '</span></div>'
    + '<div class="borda-grade">'
    + '<div><small>Link com a central</small><b class="' + (borda && borda.linkLigado ? '' : 'alerta') + '">' + linkTxt + '</b></div>'
    + '<div><small>Fila local</small><b class="' + (borda && borda.filaPendente > 0 ? 'alerta' : '') + '">' + (borda ? borda.filaPendente : '–') + '</b></div>'
    + '<div><small>Enviadas</small><b>' + (borda ? borda.enviadas : '–') + '</b></div></div>'
    + '<div class="nota">Visto pela central: ' + (n.ultima_sincronizacao ? ha(n.ultima_sincronizacao) : 'nunca')
    + (borda ? '' : ' · nó não acessível em ' + esc(EDGE_URL)) + '</div>'
    + '<div class="borda-acoes"><button class="perigo" onclick="definirLink(false)"' + (borda && borda.linkLigado ? '' : ' disabled') + '>Derrubar link</button>'
    + '<button class="ok" onclick="definirLink(true)"' + (borda && !borda.linkLigado ? '' : ' disabled') + '>Restaurar link</button></div>';
}

async function definirLink(ligado) {
  // no-cors: a requisição é enviada ao nó mesmo sem ler a resposta; o estado é relido logo em seguida
  try { await fetch(EDGE_URL + '/api/v1/borda/link?ligado=' + ligado, { method: 'POST', mode: 'no-cors' }); } catch (e) { toast('erro', 'Nó de borda', 'Não foi possível falar com o nó em ' + EDGE_URL); }
  setTimeout(carregarBorda, 500);
}

// ---------------------------------------------------------------- dispositivos
async function carregarDispositivos() {
  const pag = await api('/api/v1/dispositivos?size=200');
  dispositivos = pag.itens;
  if (anterior.dispositivos) {
    dispositivos.forEach((d) => {
      const antes = anterior.dispositivos[d.slug];
      if (antes && antes !== d.situacao) {
        registrar(d.situacao === 'ONLINE' ? 'ok' : 'alerta', d.slug + ' ficou ' + d.situacao.toLowerCase());
      }
    });
  }
  anterior.dispositivos = {};
  dispositivos.forEach((d) => { anterior.dispositivos[d.slug] = d.situacao; });

  const online = dispositivos.filter((d) => d.situacao === 'ONLINE').length;
  $('kpi-disp').textContent = online + ' / ' + dispositivos.length;
  $('kpi-disp-card').className = 'kpi ' + (online === dispositivos.length && online > 0 ? 'verde' : (online === 0 ? 'cinza' : 'agua'));
  $('kpi-disp-sub').innerHTML = '<b>' + (dispositivos.length - online) + '</b> offline';
  desenharDispositivos();
}

function desenharDispositivos() {
  const f = $('filtro').value.trim().toLowerCase();
  const lista = dispositivos.filter((d) => !f || [d.slug, d.area_slug, d.site_slug, d.situacao, TIPO[d.perfil_slug] || d.perfil_slug].join(' ').toLowerCase().includes(f));
  $('disp-resumo').textContent = lista.length + ' de ' + dispositivos.length;
  $('tabela-dispositivos').querySelector('tbody').innerHTML = lista.map((d) => '<tr><td><b>' + esc(d.slug) + '</b></td><td><span class="tipo">'
    + esc(TIPO[d.perfil_slug] || d.perfil_slug) + '</span></td><td>' + esc(nomeArea(d.area_slug)) + '</td><td>' + esc(d.site_slug) + '</td><td><span class="sit '
    + esc(d.situacao) + '"><i></i>' + esc(d.situacao) + '</span></td><td>' + (d.ultimo_visto ? ha(d.ultimo_visto) : 'nunca') + '</td></tr>').join('')
    || '<tr><td colspan="6" class="vazio">Nenhum dispositivo encontrado</td></tr>';
}

// ---------------------------------------------------------------- indicadores
async function carregarResumo() {
  const r = await api('/api/v1/painel/resumo');
  $('kpi-msg').textContent = r.mensagens.ultimoMinuto;
  $('kpi-msg-sub').innerHTML = '<b>' + r.mensagens.total24h.toLocaleString('pt-BR') + '</b> em 24 h';
  $('kpi-atrasadas').textContent = r.atrasadas7d.toLocaleString('pt-BR');
  $('kpi-recusadas').textContent = r.recusadas24h.toLocaleString('pt-BR');
  $('kpi-recusadas-card').className = 'kpi ' + (r.recusadas24h > 0 ? 'vermelho' : 'cinza');

  // últimos 15 minutos, completando os minutos sem mensagens
  const base = new Date(); base.setSeconds(0, 0);
  const rotulos = []; const valores = [];
  const porMinuto = {};
  r.mensagens.serie.forEach((p) => { porMinuto[Math.floor(new Date(p.minuto).getTime() / 60000)] = p.qtd; });
  for (let i = 14; i >= 0; i--) {
    const t = new Date(base.getTime() - i * 60000);
    rotulos.push(t.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }));
    valores.push(porMinuto[Math.floor(t.getTime() / 60000)] || 0);
  }
  if (!spark) {
    spark = new Chart($('spark'), {
      type: 'bar',
      data: { labels: rotulos, datasets: [{ data: valores, backgroundColor: '#1f7a8c', borderRadius: 2 }] },
      options: { responsive: true, maintainAspectRatio: false, animation: false, plugins: { legend: { display: false } },
        scales: { x: { display: false }, y: { display: false, beginAtZero: true } } },
    });
  } else {
    spark.data.labels = rotulos; spark.data.datasets[0].data = valores; spark.update();
  }
}

// ---------------------------------------------------------------- gráfico
async function carregarGrafico() {
  if (!areaSelecionada) { return; }
  const modo = $('modo').value;
  let fluxo; let vel;
  if (modo === 'hora') {
    const f = await api('/api/v1/areas/' + areaSelecionada + '/agregado?grandeza=fluxo_veiculos&janela=hora&horas=24');
    const v = await api('/api/v1/areas/' + areaSelecionada + '/agregado?grandeza=velocidade_media&janela=hora&horas=24');
    fluxo = f.map((p) => ({ x: Date.parse(p.periodo), y: p.media }));
    vel = v.map((p) => ({ x: Date.parse(p.periodo), y: p.media }));
  } else {
    const f = await api('/api/v1/areas/' + areaSelecionada + '/serie?grandeza=fluxo_veiculos&minutos=60');
    const v = await api('/api/v1/areas/' + areaSelecionada + '/serie?grandeza=velocidade_media&minutos=60');
    fluxo = f.map((p) => ({ x: Date.parse(p.ts), y: p.valor }));
    vel = v.map((p) => ({ x: Date.parse(p.ts), y: p.valor }));
  }
  const xs = fluxo.concat(vel).map((p) => p.x);
  const xmin = xs.length ? Math.min.apply(null, xs) : Date.now() - 3600000;
  const xmax = xs.length ? Math.max.apply(null, xs) : Date.now();
  const dados = {
    datasets: [
      { label: 'Fluxo (veíc/min)', data: fluxo, borderColor: '#1f3a5f', backgroundColor: '#1f3a5f', yAxisID: 'y', tension: 0.25, pointRadius: 0, pointHoverRadius: 4, borderWidth: 2 },
      { label: 'Velocidade média (km/h)', data: vel, borderColor: '#1f7a8c', backgroundColor: '#1f7a8c', yAxisID: 'y1', tension: 0.25, pointRadius: 0, pointHoverRadius: 4, borderWidth: 2.4 },
      { label: 'Limite de congestionamento (' + LIMITE_CONGESTIONAMENTO + ' km/h)', data: [{ x: xmin, y: LIMITE_CONGESTIONAMENTO }, { x: xmax, y: LIMITE_CONGESTIONAMENTO }],
        borderColor: '#d32f2f', borderDash: [7, 6], yAxisID: 'y1', pointRadius: 0, borderWidth: 1.6 },
    ],
  };
  if (!grafico) {
    grafico = new Chart($('grafico'), {
      type: 'line', data: dados,
      options: {
        responsive: true, maintainAspectRatio: false, animation: false, parsing: false,
        interaction: { mode: 'nearest', axis: 'x', intersect: false },
        plugins: { legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 8 } },
          tooltip: { callbacks: { title: (it) => (it.length ? new Date(it[0].parsed.x).toLocaleString('pt-BR') : '') } } },
        scales: {
          x: { type: 'linear', min: xmin, max: xmax, ticks: { maxTicksLimit: 8, callback: (v) => new Date(v).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) } },
          y: { position: 'left', title: { display: true, text: 'Fluxo (veíc/min)' }, beginAtZero: true },
          y1: { position: 'right', title: { display: true, text: 'Velocidade (km/h)' }, beginAtZero: true, grid: { drawOnChartArea: false } },
        },
      },
    });
  } else {
    grafico.data = dados;
    grafico.options.scales.x.min = xmin;
    grafico.options.scales.x.max = xmax;
    grafico.update();
  }
}

// ---------------------------------------------------------------- ciclo
let falhas = 0;
async function ciclo() {
  const tarefas = [carregarAreas, carregarAlarmes, carregarNos, carregarDispositivos, carregarResumo, carregarBorda];
  const resultados = await Promise.allSettled(tarefas.map((f) => f()));
  const centralOk = resultados.slice(0, 5).some((r) => r.status === 'fulfilled');
  resultados.forEach((r) => { if (r.status === 'rejected') { console.warn(r.reason && r.reason.message); } });
  falhas = centralOk ? 0 : falhas + 1;
  $('ao-vivo').className = 'ao-vivo' + (falhas >= 2 ? ' falha' : '');
  $('ao-vivo-texto').textContent = falhas >= 2 ? 'Sem conexão com a central' : 'Ao vivo';
  $('atualizado').textContent = falhas >= 2 ? '' : new Date().toLocaleTimeString('pt-BR');
  if (primeiraCarga && centralOk) { registrar('info', 'Painel conectado à plataforma central'); }
  primeiraCarga = false;
}

$('modo').addEventListener('change', () => carregarGrafico().catch((e) => console.warn(e.message)));
$('filtro').addEventListener('input', desenharDispositivos);
$('btn-tela-cheia').addEventListener('click', () => {
  if (document.fullscreenElement) { document.exitFullscreen(); } else { document.documentElement.requestFullscreen().catch(() => { }); }
});

ciclo();
setInterval(ciclo, 2000);
setInterval(() => carregarGrafico().catch((e) => console.warn(e.message)), 5000);
