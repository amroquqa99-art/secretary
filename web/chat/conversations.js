// Conversation sidebar: list, search/filter, open, delete, new chat,
// relative-time labels, and the mobile sidebar toggle + swipe gesture.
// Extracted verbatim from index.html's inline <script>.

import { state, config, elements, endpoints } from './session.js';
import { addMessage, escapeHtml } from './thread.js';
import { startPendingQuestionPolling, stopPendingQuestionPolling } from './pending-question.js';
import { localeState, t, translateChatUi } from './locale.js';
import { formattingLocale } from '../i18n/core.js';

export function toggleSidebar() {
  elements.sidebar.classList.toggle('open');
  elements.overlay.classList.toggle('visible');
}

export function closeSidebar() {
  elements.sidebar.classList.remove('open');
  elements.overlay.classList.remove('visible');
}

export function openSidebar() {
  elements.sidebar.classList.add('open');
  elements.overlay.classList.add('visible');
}

// Swipe gesture to open/close sidebar on mobile. Called once from initChat();
// the listeners live on `document` and only fire on touch interaction.
export function setupSwipeGestures() {
  let touchStartX = 0;
  let touchStartY = 0;
  let isSwiping = false;
  let openingDirection = 1;
  const edgeThreshold = 30; // px from the locale's start edge to trigger
  const swipeThreshold = 50; // px movement to complete swipe

  document.addEventListener('touchstart', (e) => {
    isSwiping = false;
    if (e.touches.length !== 1) return;
    touchStartX = e.touches[0].clientX;
    touchStartY = e.touches[0].clientY;
    const rtl = document.documentElement.dir === 'rtl';
    openingDirection = rtl ? -1 : 1;
    const startEdgeDistance = rtl ? window.innerWidth - touchStartX : touchStartX;
    isSwiping = startEdgeDistance < edgeThreshold || elements.sidebar.classList.contains('open');
  }, { passive: true });

  document.addEventListener('touchend', (e) => {
    if (!isSwiping) return;
    isSwiping = false;

    const touchEndX = e.changedTouches[0].clientX;
    const touchEndY = e.changedTouches[0].clientY;
    const deltaX = (touchEndX - touchStartX) * openingDirection;
    const deltaY = Math.abs(touchEndY - touchStartY);

    // Ignore if vertical movement is greater (scrolling)
    if (deltaY > Math.abs(deltaX)) return;

    if (!elements.sidebar.classList.contains('open') && deltaX > swipeThreshold) {
      openSidebar();
    } else if (elements.sidebar.classList.contains('open') && deltaX < -swipeThreshold) {
      closeSidebar();
    }
  }, { passive: true });

  document.addEventListener('touchcancel', () => { isSwiping = false; }, { passive: true });
}

export async function loadConversations() {
  try {
    // Scope the sidebar to the selected persona and backend.
    // Conversation detail (loadConversation / search) is fetched by id and is
    // not persona/backend-scoped. config.backend is null for lifeos (see
    // backend.js), so it's normalized to the "lifeos" tag the server stores.
    const personaId = config.personaId || 'primary';
    const backend = config.backend || 'lifeos';
    const response = await fetch(
      `${endpoints.conversations}?persona_id=${encodeURIComponent(personaId)}&backend=${encodeURIComponent(backend)}`
    );
    if (response.ok) {
      const data = await response.json();
      state.allConversations = data.conversations || [];
      filterConversations(); // Apply current search filter
    }
  } catch (e) {
    console.log('Could not load conversations');
  }
}

// Cache for conversation messages (for search)
const conversationMessagesCache = {};
let searchDebounceTimer = null;

export function filterConversations() {
  const searchInput = document.getElementById('conversationSearch');
  const searchTerm = (searchInput?.value || '').toLowerCase().trim();

  if (!searchTerm) {
    renderConversations(state.allConversations);
    return;
  }

  // Debounce the search to avoid too many API calls
  clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => filterConversationsAsync(searchTerm), 200);
}

async function filterConversationsAsync(searchTerm) {
  // First, filter by title (immediate results)
  const titleMatches = state.allConversations.filter(conv => {
    const title = (conv.title || t('new_conversation')).toLowerCase();
    return title.includes(searchTerm);
  });

  // Show title matches immediately
  renderConversations(titleMatches, true);

  // Then search in message content for remaining conversations
  const nonTitleMatches = state.allConversations.filter(conv => {
    const title = (conv.title || t('new_conversation')).toLowerCase();
    return !title.includes(searchTerm);
  });

  // Fetch and search message content
  const messageMatches = [];
  for (const conv of nonTitleMatches) {
    try {
      // Use cache if available
      if (!conversationMessagesCache[conv.id]) {
        const response = await fetch(`${endpoints.conversations}/${conv.id}`);
        if (response.ok) {
          const data = await response.json();
          conversationMessagesCache[conv.id] = data.messages || [];
        }
      }

      const messages = conversationMessagesCache[conv.id] || [];
      const hasMatch = messages.some(msg =>
        (msg.content || '').toLowerCase().includes(searchTerm)
      );
      if (hasMatch) {
        messageMatches.push(conv);
      }
    } catch (e) {
      // Skip conversations we can't fetch
    }
  }

  // Combine results: title matches first, then message matches
  const allMatches = [...titleMatches, ...messageMatches];
  renderConversations(allMatches);
}

// Persona suffix for the sidebar's date subtitle, e.g. "Aug 25 · (Therapist)"
// — appended only for a conversation whose persona isn't the default
// ("primary"). Resolves the display name from `config.personas` (loaded at
// boot by persona.js's loadPersonas(), same source the persona picker
// itself renders from) rather than a hardcoded name map, so a renamed or
// newly-added persona picks up automatically. Falls back to the raw
// persona_id if the id isn't (or isn't yet) in that list — e.g. a persona
// removed from config after the conversation was created.
function personaSubtitleSuffix(conv) {
  const personaId = conv.persona_id;
  if (!personaId || personaId === 'primary') return '';
  const persona = (config.personas || []).find(p => p.id === personaId);
  const label = persona ? persona.label : personaId;
  return ` · (${label})`;
}

function renderConversations(conversations, isPartial = false) {
  const { conversationsList } = elements;
  if (conversations.length === 0 && !isPartial) {
    const searchInput = document.getElementById('conversationSearch');
    const isSearching = searchInput?.value?.trim();
    const emptyText = t(isSearching ? 'no_matching_conversations' : 'no_conversations');
    conversationsList.innerHTML = `<div class="empty-conversations">${escapeHtml(emptyText)}</div>`;
    return;
  }

  conversationsList.innerHTML = conversations.map(conv => `
                <div class="conversation-item ${conv.id === state.currentConversationId ? 'active' : ''}"
                     onclick="loadConversation('${conv.id}')">
                    <div class="conversation-icon">💬</div>
                    <div class="conversation-info">
                        <div class="conversation-title" title="${escapeHtml(conv.title || t('new_conversation'))}">${escapeHtml(conv.title || t('new_conversation'))}</div>
                        <div class="conversation-date">${escapeHtml(formatDate(conv.updated_at) + personaSubtitleSuffix(conv))}</div>
                    </div>
                    <button class="conversation-delete" onclick="event.stopPropagation(); deleteConversation('${conv.id}')" title="${escapeHtml(t('delete'))}">✕</button>
                </div>
            `).join('');
}

export async function loadConversation(id) {
  state.currentConversationId = id;
  closeSidebar();
  // Drop any answer affordance/poll from the conversation that was open; if
  // this one has an outstanding question, polling restarts below.
  stopPendingQuestionPolling();

  try {
    // encodeURIComponent — `id` can arrive from the
    // `?conversation=` deep link (main.js's maybeOpenDeepLinkedConversation),
    // an unencoded URL query param, same as every other conversation-id
    // fetch site (ask-stream.js, pending-question.js).
    const response = await fetch(`${endpoints.conversations}/${encodeURIComponent(id)}`);
    if (response.ok) {
      const data = await response.json();
      elements.chatTitle.textContent = data.title || t('conversation');

      // Clear and render messages
      elements.messagesEl.innerHTML = '';
      if (data.messages && data.messages.length > 0) {
        data.messages.forEach(msg => {
          // Sources are stored directly on the message, not in metadata
          addMessage(msg.content, msg.role, msg.sources || []);
        });
      }

      // If this conversation spawned an orchestrating-persona session that is
      // awaiting an answer, re-show the affordance and resume polling.
      if (data.pending_question) {
        startPendingQuestionPolling(id);
      }

      loadConversations(); // Refresh list to show active
    }
  } catch (e) {
    console.error('Error loading conversation:', e);
  }
}

export async function deleteConversation(id) {
  if (!confirm(t('delete_conversation'))) return;

  try {
    await fetch(`${endpoints.conversations}/${id}`, { method: 'DELETE' });
    if (id === state.currentConversationId) {
      newChat();
    }
    loadConversations();
  } catch (e) {
    console.error('Error deleting conversation:', e);
  }
}

export function newChat() {
  state.currentConversationId = null;
  state.currentAgentThread = null; // leave agent-thread mode
  stopPendingQuestionPolling();   // no active conversation → no answer affordance
  elements.inputField.placeholder = t('ask_question');
  elements.chatTitle.textContent = t('new_conversation');
  elements.messagesEl.innerHTML = `
                <div class="welcome">
                    <div class="welcome-icon">🧠</div>
                    <h2 data-i18n="welcome_title">Welcome to LifeOS</h2>
                    <p data-i18n="welcome_body">Your personal knowledge assistant. Ask about your notes, calendar, emails, or anything in your vault.</p>
                    <div class="suggestions">
                        <button class="suggestion" onclick="askQuestion('What\\'s on my calendar tomorrow?')" data-i18n="calendar_tomorrow">📅 Calendar tomorrow</button>
                        <button class="suggestion" onclick="askQuestion('What are my open action items?')" data-i18n="open_action_items">✅ Open action items</button>
                        <button class="suggestion" onclick="askQuestion('Summarize my recent meeting notes')" data-i18n="recent_meetings">📝 Recent meetings</button>
                    </div>
                </div>
            `;
  translateChatUi(elements.messagesEl);
  loadConversations();
  closeSidebar();
  elements.inputField.focus();
}

export function formatDate(dateStr) {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now - date;
  const locale = localeState.locale === 'ar' ? 'ar' : 'en-US';
  const relative = (amount, unit, english) => locale === 'ar'
    ? new Intl.RelativeTimeFormat(formattingLocale(locale), { numeric: 'always' }).format(-amount, unit)
    : english;

  // Under 1 minute: "Just now"
  if (diff < 60000) return t('just_now');

  // Under 1 hour: "5m ago", "23m ago"
  if (diff < 3600000) {
    const minutes = Math.floor(diff / 60000);
    return relative(minutes, 'minute', minutes + 'm ago');
  }

  // Check if date is today (same calendar day in local timezone)
  const isToday = date.toDateString() === now.toDateString();

  // Under 24 hours AND today: "2h ago", "11h ago"
  if (diff < 86400000 && isToday) {
    const hours = Math.floor(diff / 3600000);
    return relative(hours, 'hour', hours + 'h ago');
  }

  // Check if date is yesterday
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday = date.toDateString() === yesterday.toDateString();

  // Format wall-clock time in the selected locale.
  const timeStr = date.toLocaleTimeString(formattingLocale(locale), {
    hour: 'numeric',
    minute: '2-digit',
    hour12: true
  });

  // The localized yesterday label includes wall-clock time.
  if (isYesterday) {
    return t('yesterday') + ' ' + timeStr;
  }

  // Older dates use the selected locale's month and numeral conventions.
  const monthDay = date.toLocaleDateString(formattingLocale(locale), {
    month: 'short',
    day: 'numeric'
  });

  // Check if same year
  const isSameYear = date.getFullYear() === now.getFullYear();

  // This year: "Jan 8" (short) or could include time for recent dates
  if (isSameYear) {
    // For dates within the last week, include time
    if (diff < 604800000) {
      return monthDay + (locale === 'ar' ? '، ' : ', ') + timeStr;
    }
    // Older this-year dates: just "Jan 8"
    return monthDay;
  }

  // Older (different year): "Jan 8, 2025"
  return date.toLocaleDateString(formattingLocale(locale), {
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  });
}
