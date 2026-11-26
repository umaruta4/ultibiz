/* UltiBiz assistant drawer: the chat, the citations and the proposed actions. */
(function () {
  'use strict';

  var conversationId = null;
  var history = [];

  function csrf() {
    var meta = document.querySelector('meta[name="_csrf"]');
    return meta ? meta.content : '';
  }

  function element(tag, className, text) {
    var el = document.createElement(tag);
    if (className) { el.className = className; }
    if (text !== undefined && text !== null) { el.textContent = text; }
    return el;
  }

  function log(role, text) {
    var log = document.getElementById('ai-log');
    if (!log) { return null; }
    var row = element('div', 'ub-ai-message ub-ai-' + role);
    row.appendChild(element('div', 'ub-ai-role', role === 'user' ? 'You' : 'Assistant'));
    row.appendChild(element('div', 'ub-ai-text', text));
    log.appendChild(row);
    log.scrollTop = log.scrollHeight;
    return row;
  }

  function showCitations(citations) {
    var log = document.getElementById('ai-log');
    if (!log || !citations || !citations.length) { return; }
    var box = element('div', 'ub-ai-citations');
    box.appendChild(element('div', 'ub-ai-role', 'Sources'));
    citations.forEach(function (citation) {
      var line = element('div', 'ub-ai-citation');
      line.appendChild(element('span', 'ub-ai-citation-title',
        '[' + citation.index + '] ' + citation.documentTitle));
      if (citation.source) {
        line.appendChild(element('a', 'ub-ai-citation-link', citation.source));
      }
      box.appendChild(line);
    });
    log.appendChild(box);
    log.scrollTop = log.scrollHeight;
  }

  function proposalCard(call) {
    var log = document.getElementById('ai-log');
    if (!log) { return; }
    var card = element('div', 'ub-ai-proposal');
    card.appendChild(element('div', 'ub-ai-role', 'Proposed action'));
    card.appendChild(element('div', 'ub-ai-proposal-title', 'Draft a journal entry'));
    var diff = element('pre', 'ub-ai-diff', call.diff || '');
    card.appendChild(diff);
    var actions = element('div', 'd-flex gap-2 mt-2');
    var confirmButton = element('button', 'btn btn-sm btn-primary', 'Confirm');
    confirmButton.type = 'button';
    confirmButton.addEventListener('click', function () {
      fetch('/ai/actions/' + call.id + '/confirm', {
        method: 'POST',
        headers: { 'X-CSRF-TOKEN': csrf() }
      }).then(function (response) { return response.json(); }).then(function (result) {
        confirmButton.disabled = true;
        card.appendChild(element('div', 'ub-ai-proposal-result', result.result || result.status));
      });
    });
    var rejectButton = element('button', 'btn btn-sm btn-outline-secondary', 'Reject');
    rejectButton.type = 'button';
    rejectButton.addEventListener('click', function () {
      fetch('/ai/actions/' + call.id + '/reject', {
        method: 'POST',
        headers: { 'X-CSRF-TOKEN': csrf() }
      }).then(function () {
        rejectButton.disabled = true;
        confirmButton.disabled = true;
        card.appendChild(element('div', 'ub-ai-proposal-result', 'rejected'));
      });
    });
    actions.appendChild(confirmButton);
    actions.appendChild(rejectButton);
    card.appendChild(actions);
    log.appendChild(card);
    log.scrollTop = log.scrollHeight;
  }

  function send() {
    var input = document.getElementById('ai-question');
    if (!input) { return; }
    var question = input.value.trim();
    if (!question) { return; }
    input.value = '';
    log('user', question);
    var answer = log('assistant', '…');
    var body = {
      question: question,
      route: window.location.pathname,
      entity: document.body.getAttribute('data-entity') || null,
      filters: {},
      conversationId: conversationId
    };
    fetch('/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf() },
      body: JSON.stringify(body)
    }).then(function (response) {
      if (!response.ok) { throw new Error('HTTP ' + response.status); }
      return response.json();
    }).then(function (result) {
      conversationId = result.conversationId;
      var text = answer.querySelector('.ub-ai-text');
      if (text) { text.textContent = result.answer; }
      if (result.degraded) {
        answer.classList.add('ub-ai-degraded');
      }
      showCitations(result.citations);
    }).catch(function (error) {
      var text = answer.querySelector('.ub-ai-text');
      if (text) { text.textContent = 'The assistant is not available: ' + error.message; }
      answer.classList.add('ub-ai-degraded');
    });
  }

  function proposeJournal() {
    var description = document.getElementById('ai-journal-description');
    var amount = document.getElementById('ai-journal-amount');
    var debit = document.getElementById('ai-journal-debit');
    var credit = document.getElementById('ai-journal-credit');
    var params = new URLSearchParams();
    params.set('tool', 'draft_journal');
    if (conversationId) { params.set('conversationId', conversationId); }
    if (description) { params.set('description', description.value); }
    if (amount) { params.set('amount', amount.value); }
    if (debit) { params.set('debitAccount', debit.value); }
    if (credit) { params.set('creditAccount', credit.value); }
    fetch('/ai/actions/propose', {
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': csrf(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    }).then(function (response) { return response.json(); }).then(proposalCard);
  }

  function init() {
    var sendButton = document.getElementById('ai-send');
    if (sendButton) {
      sendButton.addEventListener('click', send);
    }
    var input = document.getElementById('ai-question');
    if (input) {
      input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
          event.preventDefault();
          send();
        }
      });
    }
    var proposeButton = document.getElementById('ai-propose-journal');
    if (proposeButton) {
      proposeButton.addEventListener('click', proposeJournal);
    }
    // Ctrl/⌘+K opens the assistant
    document.addEventListener('keydown', function (event) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        var drawer = document.getElementById('ai-drawer');
        if (drawer && window.bootstrap) {
          window.bootstrap.Offcanvas.getOrCreateInstance(drawer).show();
        }
        var question = document.getElementById('ai-question');
        if (question) { setTimeout(function () { question.focus(); }, 200); }
      }
    });
  }

  window.UltiBizAssistant = { send: send, proposeJournal: proposalJournal };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
