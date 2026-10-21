// Painel de mobilidade: mapa (Leaflet), gráficos (Chart.js) e tabelas, atualizados a cada 2 segundos.
const CORES = { FLUIDO: '#2e7d32', MODERADO: '#f9a825', CONGESTIONADO: '#c62828', SEM_DADOS: '#9e9e9e' };
const mapa = L.map('mapa').setView([-16.318, -48.95], 14);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' }).addTo(mapa);

const marcadores = {};
let areaSelecionada = null;
let grafico = null;

async function api(url) {
  const r = await fetch(url, { credentials: 'same-origin' });
  if (r.redirected || r.status === 401) { window.location.href = '/login'; throw new Error('sessão expirada'); }
  if (!r.ok) { throw new Error(url + ' -> ' + r.status); }
  return r.json();
}
const num = (v, d = 1) => (v === null || v === undefined ? '–' : Number(v).toFixed(d));
const hora = (iso) => (iso ? new Date(iso).toLocaleString('pt-BR') : '–');
const esc = (t) => String(t ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function carregarAreas() {
  const areas = await api('/api/v1/areas/estado');
  let centro = [];
  areas.forEach((a) => {
    if (a.lat === null || a.lon === null) { return; }
    centro.push([a.lat, a.lon]);
    const cor = CORES[a.nivel] || CORES.SEM_DADOS;
    if (!marcadores[a.slug]) {
      marcadores[a.slug] = L.circleMarker([a.lat, a.lon], { radius: 14, weight: 3, color: '#1f3a5f', fillOpacity: 0.9 })
        .addTo(mapa).on('click', () => selecionar(a.slug, a.nome));
      if (areas.length && Object.keys(marcadores).length === areas.length) { mapa.fitBounds(centro, { padding: [40, 40] }); }
    }
    marcadores[a.slug].setStyle({ fillColor: cor });
    if (a.nivel === 'CONGESTIONADO') { marcadores[a.slug].bringToFront(); }
    const texto = '<b>' + esc(a.nome) + '</b><br>Velocidade: ' + num(a.velocidade) + ' km/h<br>Fluxo: ' + num(a.fluxo, 0)
      + ' veíc/min<br>Ocupação: ' + num(a.ocupacao, 0) + '%<br>Alarmes: ' + a.alarmes;
    marcadores[a.slug].unbindTooltip().bindTooltip(texto);
  });
  document.getElementById('atualizado').textContent = 'atualizado às ' + new Date().toLocaleTimeString('pt-BR');
}

async function carregarAlarmes() {
  const pag = await api('/api/v1/alarmes?estado=ABERTOS&size=50');
  document.getElementById('qtd-alarmes').textContent = pag.total;
  const tb = document.querySelector('#tabela-alarmes tbody');
  tb.innerHTML = pag.itens.map((a) => '<tr><td><span class="tag ' + esc(a.severidade) + '">' + esc(a.severidade) + '</span></td><td>'
    + esc(a.regra_nome) + '</td><td>' + esc(a.area_slug) + '</td><td>' + hora(a.aberto_em) + '</td><td>' + esc(a.estado) + '</td><td>'
    + (a.estado === 'ATIVO' ? '<button onclick="reconhecer(' + a.id + ')">Reconhecer</button>' : '') + '</td></tr>').join('')
    || '<tr><td colspan="6">Nenhum alarme aberto</td></tr>';
}

async function reconhecer(id) {
  const r = await fetch('/api/v1/alarmes/' + id + '/reconhecer', { method: 'POST', credentials: 'same-origin' });
  if (!r.ok) { alert('Não foi possível reconhecer o alarme (' + r.status + ')'); }
  carregarAlarmes();
}

async function carregarNos() {
  const nos = await api('/api/v1/nos-borda');
  const tb = document.querySelector('#tabela-nos tbody');
  tb.innerHTML = nos.map((n) => '<tr><td>' + esc(n.nome) + '</td><td><span class="tag ' + esc(n.situacao) + '">' + esc(n.situacao.replace('_', ' ')) + '</span></td><td>'
    + (n.ultima_sincronizacao ? hora(n.ultima_sincronizacao) + ' (há ' + n.segundos_sem_sincronizar + ' s)' : 'nunca') + '</td><td>' + n.fila_pendente + '</td></tr>').join('')
    || '<tr><td colspan="4">Nenhum nó cadastrado</td></tr>';
}

async function carregarDispositivos() {
  const pag = await api('/api/v1/dispositivos?size=100');
  const tb = document.querySelector('#tabela-dispositivos tbody');
  tb.innerHTML = pag.itens.map((d) => '<tr><td>' + esc(d.slug) + '</td><td>' + esc(d.area_slug) + '</td><td><span class="tag ' + esc(d.situacao) + '">'
    + esc(d.situacao) + '</span></td><td>' + hora(d.ultimo_visto) + '</td></tr>').join('');
}

function selecionar(slug, nome) {
  areaSelecionada = slug;
  document.getElementById('area-selecionada').textContent = nome;
  carregarGrafico();
}

async function carregarGrafico() {
  if (!areaSelecionada) { return; }
  const modo = document.getElementById('modo').value;
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
  const dados = {
    datasets: [
      { label: 'Fluxo (veíc/min)', data: fluxo, borderColor: '#1f3a5f', backgroundColor: '#1f3a5f', yAxisID: 'y', tension: 0.2, pointRadius: 2 },
      { label: 'Velocidade média (km/h)', data: vel, borderColor: '#1f7a8c', backgroundColor: '#1f7a8c', yAxisID: 'y1', tension: 0.2, pointRadius: 2 },
    ],
  };
  if (!grafico) {
    grafico = new Chart(document.getElementById('grafico'), {
      type: 'line', data: dados,
      options: {
        responsive: true, maintainAspectRatio: false, animation: false, parsing: false,
        scales: {
          x: { type: 'linear', ticks: { callback: (v) => new Date(v).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) } },
          y: { position: 'left', title: { display: true, text: 'Fluxo (veíc/min)' }, beginAtZero: true },
          y1: { position: 'right', title: { display: true, text: 'Velocidade (km/h)' }, beginAtZero: true, grid: { drawOnChartArea: false } },
        },
      },
    });
  } else {
    grafico.data = dados;
    grafico.update();
  }
}

document.getElementById('modo').addEventListener('change', carregarGrafico);

function ciclo() {
  [carregarAreas, carregarAlarmes, carregarNos, carregarDispositivos].forEach((f) => f().catch((e) => console.warn(e.message)));
}
ciclo();
setInterval(ciclo, 2000);
setInterval(() => carregarGrafico().catch((e) => console.warn(e.message)), 5000);
