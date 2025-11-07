// Application state
let sessions = [];
let currentSessionId = null;
let tools = [];

// DOM elements
const sessionList = document.getElementById('session-list');
const messagesContainer = document.getElementById('messages');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const newSessionBtn = document.getElementById('new-session-btn');
const chatTitle = document.getElementById('chat-title');
const renameSessionBtn = document.getElementById('rename-session-btn');
const clearSessionBtn = document.getElementById('clear-session-btn');
const deleteSessionBtn = document.getElementById('delete-session-btn');
const toolsList = document.getElementById('tools-list');
const refreshToolsBtn = document.getElementById('refresh-tools-btn');

// User info elements
const userInitials = document.getElementById('user-initials');
const userName = document.getElementById('user-name');
const userLogin = document.getElementById('user-login');

// Sidebar toggle elements
const sidebarToggleBtn = document.getElementById('sidebar-toggle-btn');
const sidebar = document.querySelector('.sidebar');
const mainContent = document.querySelector('.main-content');

// Speech Recognition Setup
let recognition = null;
let isListening = false;

// Initialize speech recognition
function initSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

    if (!SpeechRecognition) {
        console.warn('Speech recognition not supported in this browser');
        document.getElementById('dictate-btn').style.display = 'none';
        return;
    }

    recognition = new SpeechRecognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = 'en-US';

    const dictateBtn = document.getElementById('dictate-btn');
    const messageInput = document.getElementById('message-input');

    recognition.onstart = function() {
        isListening = true;
        dictateBtn.classList.add('listening');
        console.log('Speech recognition started');
    };

    recognition.onresult = function(event) {
        let interimTranscript = '';
        let finalTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; i++) {
            const transcript = event.results[i][0].transcript;
            if (event.results[i].isFinal) {
                finalTranscript += transcript + ' ';
            } else {
                interimTranscript += transcript;
            }
        }

        const currentText = messageInput.value;

        if (finalTranscript) {
            messageInput.value = currentText + finalTranscript;
        } else if (interimTranscript) {
            messageInput.placeholder = `Listening... "${interimTranscript}"`;
        }
    };

    recognition.onerror = function(event) {
        console.error('Speech recognition error:', event.error);
        stopListening();

        switch(event.error) {
            case 'no-speech':
                alert('No speech detected. Please try again.');
                break;
            case 'not-allowed':
                alert('Microphone access denied. Please enable microphone permissions.');
                break;
            default:
                alert('Speech recognition error: ' + event.error);
        }
    };

    recognition.onend = function() {
        stopListening();
    };

    dictateBtn.addEventListener('click', function() {
        if (isListening) {
            recognition.stop();
        } else {
            messageInput.placeholder = 'Type your message here...';
            recognition.start();
        }
    });
}

function stopListening() {
    isListening = false;
    const dictateBtn = document.getElementById('dictate-btn');
    const messageInput = document.getElementById('message-input');

    dictateBtn.classList.remove('listening');
    messageInput.placeholder = 'Type your message here...';
}

// Load user information from server
async function loadUserInfo() {
    console.log('Loading user info...');
    try {
        const response = await fetch('http://127.0.0.1:9090/bff/userinfo', {
            method: 'GET',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            credentials: 'include'
        });

        console.log('Response status:', response.status);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const userInfo = await response.json();
        console.log('Raw user info response:', userInfo);

        if (userInfo.name) {
            userName.textContent = userInfo.name;
            const initials = userInfo.name
                .split(' ')
                .map(word => word.charAt(0))
                .join('')
                .substring(0, 2)
                .toUpperCase();
            userInitials.textContent = initials;
        } else {
            userName.textContent = 'Unknown User';
            userInitials.textContent = 'UU';
        }

        const loginField = userInfo.login || userInfo.userLogin || userInfo.username || userInfo.userId;
        if (loginField) {
            userLogin.textContent = `@${loginField}`;
        } else {
            userLogin.textContent = '@unknown';
        }

        console.log('User info loaded successfully:', userInfo);
    } catch (error) {
        console.error('Failed to load user info:', error);
        userName.textContent = 'User';
        userLogin.textContent = 'Service unavailable';
        userInitials.textContent = 'U';
    }
}

// Initialize app
document.addEventListener('DOMContentLoaded', function() {
    initSpeechRecognition();
    loadSessions();
    loadTools();
    loadUserInfo();
    setupEventListeners();
    restoreSidebarState();
});

window.addEventListener('resize', handleResize);

document.addEventListener('keydown', function(e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
        e.preventDefault();
        toggleSidebar();
    }
});

// Setup event listeners
function setupEventListeners() {
    sendBtn.addEventListener('click', sendMessage);
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    newSessionBtn.addEventListener('click', createNewSession);
    renameSessionBtn.addEventListener('click', renameSession);
    clearSessionBtn.addEventListener('click', clearCurrentSession);
    deleteSessionBtn.addEventListener('click', deleteCurrentSession);
    refreshToolsBtn.addEventListener('click', loadTools);
    sidebarToggleBtn.addEventListener('click', toggleSidebar);
}

// Toggle sidebar visibility
function toggleSidebar() {
    const isHidden = sidebar.classList.contains('hidden');
    const isMobile = window.innerWidth <= 768;

    if (isHidden) {
        sidebar.classList.remove('hidden');
        mainContent.classList.remove('sidebar-hidden');
        sidebarToggleBtn.innerHTML = '<span>&lt;&lt;</span>';
        sidebarToggleBtn.title = 'Hide sidebar';

        if (isMobile) {
            sidebar.classList.add('open');
        }
    } else {
        sidebar.classList.add('hidden');
        mainContent.classList.add('sidebar-hidden');
        sidebarToggleBtn.innerHTML = '<span>&gt;&gt;</span>';
        sidebarToggleBtn.title = 'Show sidebar';

        if (isMobile) {
            sidebar.classList.remove('open');
        }
    }

    localStorage.setItem('sidebarHidden', isHidden ? 'false' : 'true');
}

// Restore sidebar state from localStorage
function restoreSidebarState() {
    const sidebarHidden = localStorage.getItem('sidebarHidden') === 'true';
    const isMobile = window.innerWidth <= 768;

    if (sidebarHidden) {
        sidebar.classList.add('hidden');
        mainContent.classList.add('sidebar-hidden');
        sidebarToggleBtn.innerHTML = '<span>&gt;&gt;</span>';
        sidebarToggleBtn.title = 'Show sidebar';

        if (isMobile) {
            sidebar.classList.remove('open');
        }
    } else {
        sidebar.classList.remove('hidden');
        mainContent.classList.remove('sidebar-hidden');
        sidebarToggleBtn.innerHTML = '<span>&lt;&lt;</span>';
        sidebarToggleBtn.title = 'Hide sidebar';

        if (isMobile) {
            sidebar.classList.add('open');
        }
    }
}

// Handle window resize to adjust mobile behavior
function handleResize() {
    const isMobile = window.innerWidth <= 768;
    const isHidden = sidebar.classList.contains('hidden');

    if (isMobile) {
        if (!isHidden) {
            sidebar.classList.add('open');
        } else {
            sidebar.classList.remove('open');
        }
    } else {
        sidebar.classList.remove('open');
    }
}

// Load sessions from server
async function loadSessions() {
    try {
        const response = await fetch('/bff/ai_backend/mcp/api/sessions');
        sessions = await response.json();

        if (sessions.length === 0) {
            await createNewSession();
        } else {
            renderSessionList();
            selectSession(sessions[0].id);
        }
    } catch (error) {
        console.error('Error loading sessions:', error);
        showError('Failed to load sessions');
    }
}

// Render session list
function renderSessionList() {
    sessionList.innerHTML = '';

    sessions.forEach(session => {
        const sessionItem = document.createElement('div');
        sessionItem.className = 'session-item';
        if (session.id === currentSessionId) {
            sessionItem.classList.add('active');
        }

        sessionItem.innerHTML = `
            <span class="session-name">${escapeHtml(session.name)}</span>
            <span class="session-time">${formatTime(session.lastActivity)}</span>
        `;

        sessionItem.addEventListener('click', () => selectSession(session.id));
        sessionList.appendChild(sessionItem);
    });
}

// Select a session
function selectSession(sessionId) {
    currentSessionId = sessionId;
    const session = sessions.find(s => s.id === sessionId);

    if (session) {
        chatTitle.textContent = session.name;
        renderMessages(session.messages);
        renderSessionList();
    }
}

// Render messages
function renderMessages(messages) {
    messagesContainer.innerHTML = '';

    if (!messages || messages.length === 0) {
        messagesContainer.innerHTML = `
            <div class="empty-state">
                <h3>No messages yet</h3>
                <p>Start a conversation by typing a message below</p>
            </div>
        `;
        return;
    }

    messages.forEach(message => {
        appendMessage(message);
    });

    scrollToBottom();
}

// Append a single message
function appendMessage(message) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${message.role}`;
    messageDiv.setAttribute('data-message-id', message.id);

    const avatar = message.role === 'user' ? 'U' : 'AI';

    const formattedContent = message.role === 'assistant' ?
        formatMarkdown(message.content) :
        escapeHtml(message.content);

    const feedbackButtons = message.role === 'assistant' ? `
        <div class="message-feedback">
            <button class="feedback-btn thumbs-up ${message.liked === true ? 'active' : ''}" 
                    onclick="provideFeedback('${message.id}', true)" 
                    title="Thumbs up">
                👍
            </button>
            <button class="feedback-btn thumbs-down ${message.liked === false ? 'active' : ''}" 
                    onclick="provideFeedback('${message.id}', false)" 
                    title="Thumbs down">
                👎
            </button>
        </div>
    ` : '';

    const streamingIndicator = message.role === 'assistant' && !message.content ?
        '<div class="streaming-indicator"><span></span><span></span><span></span></div>' : '';

    messageDiv.innerHTML = `
        <div class="message-avatar">${avatar}</div>
        <div class="message-wrapper">
            <div class="message-content">${formattedContent || streamingIndicator}</div>
            <div class="message-footer">
                <div class="message-time">${formatTime(message.timestamp)}</div>
                ${feedbackButtons}
            </div>
        </div>
    `;

    messagesContainer.appendChild(messageDiv);
    scrollToBottom();
}

// Send message with streaming support
async function sendMessage() {
    const messageText = messageInput.value.trim();

    if (!messageText || !currentSessionId) {
        return;
    }

    messageInput.disabled = true;
    sendBtn.disabled = true;
    sendBtn.innerHTML = '<div class="loading"></div>';

    const userMessage = {
        id: generateTempId(),
        role: 'user',
        content: messageText,
        timestamp: new Date().toISOString()
    };
    appendMessage(userMessage);

    messageInput.value = '';

    let assistantMessageId = null;
    let assistantContent = '';

    try {
        const response = await fetch('/bff/ai_backend/mcp/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({
                sessionId: currentSessionId,
                message: messageText
            })
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();

        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();

            if (done) {
                break;
            }

            buffer += decoder.decode(value, { stream: true });

            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.substring(5).trim();

                    if (data) {
                        try {
                            const event = JSON.parse(data);

                            if (event.messageId && !assistantMessageId) {
                                assistantMessageId = event.messageId;
                                const assistantMessage = {
                                    id: assistantMessageId,
                                    role: 'assistant',
                                    content: '',
                                    timestamp: new Date().toISOString()
                                };
                                appendMessage(assistantMessage);
                            } else if (event.content) {
                                assistantContent += event.content;
                                updateMessageContent(assistantMessageId, assistantContent);
                            } else if (event.error) {
                                showError(event.error);
                            }
                        } catch (e) {
                            console.error('Error parsing SSE data:', e, data);
                        }
                    }
                }
            }
        }

        await refreshCurrentSession();

    } catch (error) {
        console.error('Error sending message:', error);
        showError('Failed to send message: ' + error.message);
    } finally {
        messageInput.disabled = false;
        sendBtn.disabled = false;
        sendBtn.textContent = 'Send';
        messageInput.focus();
    }
}

// Generate a temporary ID for messages
function generateTempId() {
    return 'temp_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
}

// Update message content during streaming
function updateMessageContent(messageId, content) {
    const messageElement = document.querySelector(`[data-message-id="${messageId}"]`);
    if (messageElement) {
        const contentElement = messageElement.querySelector('.message-content');
        if (contentElement) {
            contentElement.innerHTML = formatMarkdown(content);
            scrollToBottom();
        }
    }
}

// Create new session
async function createNewSession() {
    try {
        const sessionCount = sessions.length + 1;
        const response = await fetch('/bff/ai_backend/mcp/api/sessions', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                name: `Chat ${sessionCount}`
            })
        });

        const newSession = await response.json();
        sessions.push(newSession);
        renderSessionList();
        selectSession(newSession.id);
    } catch (error) {
        console.error('Error creating session:', error);
        showError('Failed to create new session');
    }
}

// Rename session
async function renameSession() {
    if (!currentSessionId) return;

    const session = sessions.find(s => s.id === currentSessionId);
    const newName = prompt('Enter new name for this chat:', session.name);

    if (newName && newName.trim()) {
        try {
            const response = await fetch(`/bff/ai_backend/mcp/api/sessions/${currentSessionId}/name`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    name: newName.trim()
                })
            });

            const data = await response.json();

            if (data.success) {
                session.name = newName.trim();
                chatTitle.textContent = session.name;
                renderSessionList();
            } else {
                showError('Failed to rename session');
            }
        } catch (error) {
            console.error('Error renaming session:', error);
            showError('Failed to rename session');
        }
    }
}

// Clear current session
async function clearCurrentSession() {
    if (!currentSessionId) return;

    if (!confirm('Are you sure you want to clear all messages in this chat?')) {
        return;
    }

    try {
        const response = await fetch(`/bff/ai_backend/mcp/api/sessions/${currentSessionId}/messages`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            await refreshCurrentSession();
        } else {
            showError('Failed to clear session');
        }
    } catch (error) {
        console.error('Error clearing session:', error);
        showError('Failed to clear session');
    }
}

// Delete current session
async function deleteCurrentSession() {
    if (!currentSessionId) return;

    if (sessions.length === 1) {
        alert('Cannot delete the last session. Create a new one first.');
        return;
    }

    if (!confirm('Are you sure you want to delete this chat?')) {
        return;
    }

    try {
        const response = await fetch(`/bff/ai_backend/mcp/api/sessions/${currentSessionId}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            sessions = sessions.filter(s => s.id !== currentSessionId);
            renderSessionList();

            if (sessions.length > 0) {
                selectSession(sessions[0].id);
            }
        } else {
            showError('Failed to delete session');
        }
    } catch (error) {
        console.error('Error deleting session:', error);
        showError('Failed to delete session');
    }
}

// Refresh current session from server
async function refreshCurrentSession() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/bff/ai_backend/mcp/api/sessions/${currentSessionId}`);
        const updatedSession = await response.json();

        const index = sessions.findIndex(s => s.id === currentSessionId);
        if (index !== -1) {
            sessions[index] = updatedSession;
            renderMessages(updatedSession.messages);
            renderSessionList();
        }
    } catch (error) {
        console.error('Error refreshing session:', error);
    }
}

// Utility functions
function formatTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now - date;
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;

    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;

    const diffDays = Math.floor(diffHours / 24);
    if (diffDays < 7) return `${diffDays}d ago`;

    return date.toLocaleDateString();
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Format markdown content for LLM responses
function formatMarkdown(text) {
    if (!text) return '';

    let formatted = escapeHtml(text);

    formatted = formatted.replace(/```(\w*)\n([\s\S]*?)\n```/g, (match, language, code) => {
        return `<div class="code-block">
            ${language ? `<div class="code-language">${language}</div>` : ''}
            <pre><code>${code}</code></pre>
            <button class="copy-code-btn" onclick="copyToClipboard(this)" title="Copy code">📋</button>
        </div>`;
    });

    formatted = formatted.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');
    formatted = formatted.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    formatted = formatted.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    formatted = formatted.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    formatted = formatted.replace(/_([^_]+)_/g, '<em>$1</em>');
    formatted = formatted.replace(/^### (.+)$/gm, '<h3>$1</h3>');
    formatted = formatted.replace(/^## (.+)$/gm, '<h2>$1</h2>');
    formatted = formatted.replace(/^# (.+)$/gm, '<h1>$1</h1>');
    formatted = formatted.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>');
    formatted = formatted.replace(/^(---|\*\*\*)$/gm, '<hr>');
    formatted = formatted.replace(/^[\s]*[-\*] (.+)$/gm, '<li>$1</li>');
    formatted = formatted.replace(/^[\s]*\d+\. (.+)$/gm, '<li class="numbered">$1</li>');

    formatted = formatted.replace(/(<li(?:\s+class="[^"]*")?>[^<]*<\/li>\s*)+/g, (match) => {
        if (match.includes('class="numbered"')) {
            return `<ol>${match.replace(/class="numbered"/g, '')}</ol>`;
        } else {
            return `<ul>${match}</ul>`;
        }
    });

    const tableRegex = /^(\|.+\|)\s*\n(\|[-\s\|:]+\|)\s*\n((?:\|.+\|\s*\n?)*)/gm;
    formatted = formatted.replace(tableRegex, (match, header, separator, body) => {
        const headerCells = header.split('|').slice(1, -1).map(cell => `<th>${cell.trim()}</th>`).join('');
        const bodyRows = body.trim().split('\n').map(row => {
            if (row.includes('|')) {
                const cells = row.split('|').slice(1, -1).map(cell => `<td>${cell.trim()}</td>`).join('');
                return `<tr>${cells}</tr>`;
            }
            return '';
        }).filter(row => row).join('');

        return `<div class="table-container">
            <table class="markdown-table">
                <thead><tr>${headerCells}</tr></thead>
                <tbody>${bodyRows}</tbody>
            </table>
        </div>`;
    });

    formatted = formatted.replace(/\n\n/g, '</p><p>');
    formatted = formatted.replace(/\n/g, '<br>');
    formatted = `<p>${formatted}</p>`;

    formatted = formatted.replace(/<p><\/p>/g, '');
    formatted = formatted.replace(/<p><br><\/p>/g, '');
    formatted = formatted.replace(/<p>(<(?:div|table|ul|ol|h[1-6]))/g, '$1');
    formatted = formatted.replace(/(<\/(?:div|table|ul|ol|h[1-6])>)<\/p>/g, '$1');

    return formatted;
}

function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

function showError(message) {
    alert(message);
}

// Copy code to clipboard
function copyToClipboard(button) {
    const codeBlock = button.parentElement;
    const code = codeBlock.querySelector('code').textContent;

    navigator.clipboard.writeText(code).then(() => {
        const originalText = button.textContent;
        button.textContent = '✅';
        button.style.color = '#28a745';

        setTimeout(() => {
            button.textContent = originalText;
            button.style.color = '';
        }, 2000);
    }).catch(err => {
        console.error('Failed to copy: ', err);
        button.textContent = '❌';
        setTimeout(() => {
            button.textContent = '📋';
        }, 2000);
    });
}

// Load available tools from MCP server
async function loadTools() {
    toolsList.innerHTML = '<div class="tools-loading">Loading tools...</div>';

    try {
        const response = await fetch('/bff/ai_backend/mcp/api/tools');
        tools = await response.json();
        renderTools();
    } catch (error) {
        console.error('Error loading tools:', error);
        toolsList.innerHTML = '<div class="tools-empty">Unable to load tools</div>';
    }
}

// Render tools list
function renderTools() {
    if (!tools || tools.length === 0) {
        toolsList.innerHTML = '<div class="tools-empty">No tools available</div>';
        return;
    }

    toolsList.innerHTML = '';

    tools.forEach(tool => {
        const toolItem = document.createElement('div');
        toolItem.className = 'tool-item';
        toolItem.title = tool.description || tool.name;

        toolItem.innerHTML = `
            <div class="tool-name">${escapeHtml(tool.name)}</div>
            ${tool.description ? `<div class="tool-description">${escapeHtml(tool.description)}</div>` : ''}
        `;

        toolsList.appendChild(toolItem);
    });
}

// Provide feedback on AI responses
async function provideFeedback(messageId, liked) {
    if (!currentSessionId) {
        console.error('No active session');
        return;
    }

    try {
        const response = await fetch('/bff/ai_backend/mcp/api/feedback', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                sessionId: currentSessionId,
                messageId: messageId,
                liked: liked
            })
        });

        const data = await response.json();

        if (data.success) {
            const session = sessions.find(s => s.id === currentSessionId);
            if (session) {
                const message = session.messages.find(m => m.id === messageId);
                if (message) {
                    message.liked = liked;
                    renderMessages(session.messages);
                }
            }
            console.log('Feedback recorded:', liked ? 'thumbs up' : 'thumbs down');
        } else {
            console.error('Failed to record feedback:', data.message);
        }
    } catch (error) {
        console.error('Error providing feedback:', error);
    }
}