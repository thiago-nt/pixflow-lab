const byId = id => document.getElementById(id);
let running = true;

byId('key').value = crypto.randomUUID();
byId('newKey').onclick = () => { byId('key').value = crypto.randomUUID(); };

function appendText(parent, tag, value) {
  const element = document.createElement(tag);
  element.textContent = String(value ?? '');
  parent.appendChild(element);
}

function paymentCard(payment) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.id = payment.id;
  appendText(button, 'b', payment.status);
  button.appendChild(document.createElement('br'));
  button.appendChild(document.createTextNode(payment.id));
  button.appendChild(document.createElement('br'));
  button.appendChild(document.createTextNode(`R$ ${payment.money.amount}`));
  button.appendChild(document.createElement('br'));
  button.appendChild(document.createTextNode(payment.merchantReference));
  button.onclick = async () => {
    const response = await fetch(`/internal/lab/payments/${encodeURIComponent(button.dataset.id)}`);
    const detail = await response.json();
    const outbox = detail.outbox?.[0];
    const publication = outbox?.publishedAt ? 'KAFKA_PUBLISHED' : 'OUTBOX_PENDING';
    byId('detailText').textContent = `Publicação: ${publication}\nEventId: ${outbox?.eventId ?? 'indisponível'}\n\n${JSON.stringify(detail, null, 2)}`;
    byId('detail').showModal();
  };
  return button;
}

async function refresh() {
  if (!running || document.hidden) return;
  const response = await fetch('/internal/lab/payments?size=50');
  const page = await response.json();
  const cards = byId('cards');
  cards.replaceChildren(...page.items.map(paymentCard));
  byId('updated').textContent = `Atualizado ${new Date().toLocaleTimeString()}`;
}

byId('create').onsubmit = async event => {
  event.preventDefault();
  const startedAt = performance.now();
  const response = await fetch('/api/v1/payments', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': byId('key').value
    },
    body: JSON.stringify({
      accountId: byId('account').value,
      amount: byId('amount').value,
      currency: 'BRL',
      merchantReference: byId('reference').value,
      simulationScenario: byId('scenario').value
    })
  });
  const body = await response.json();
  byId('result').textContent = `HTTP ${response.status} · ${Math.round(performance.now() - startedAt)} ms\n${JSON.stringify(body, null, 2)}`;
  refresh();
};

byId('refresh').onclick = refresh;
byId('pause').onclick = () => {
  running = !running;
  byId('pause').textContent = running ? 'Pausar' : 'Retomar';
  if (running) refresh();
};
document.addEventListener('visibilitychange', refresh);
setInterval(refresh, 3000);
refresh();
