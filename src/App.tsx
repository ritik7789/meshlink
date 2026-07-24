import React, { useState, useEffect } from 'react';
import { 
  Wifi, 
  WifiOff, 
  Send, 
  MessageSquare, 
  Radio, 
  Network,
  Activity,
  CheckCircle2,
  Clock,
  ArrowLeft,
  Users
} from 'lucide-react';
import './App.css';

type Tab = 'chat' | 'broadcast' | 'network';

interface Message {
  id: string;
  text: string;
  sent: boolean;
  time: string;
  status: 'sent' | 'delivered' | 'pending';
}

interface Contact {
  id: string;
  name: string;
  nodeId: string;
  type: 'direct' | 'channel';
  lastMessage?: string;
  unread: number;
  online: boolean;
}

const mockContacts: Contact[] = [
  { id: 'c1', name: 'Jane Doe', nodeId: '0x8A2B', type: 'direct', lastMessage: 'Yes, loud and clear.', unread: 0, online: true },
  { id: 'c2', name: 'Family Group', nodeId: 'Chan-42', type: 'channel', lastMessage: 'We reached the shelter.', unread: 2, online: false },
  { id: 'c3', name: 'Rescue Volunteer', nodeId: '0x1F9C', type: 'direct', lastMessage: 'ETA 15 mins.', unread: 0, online: true }
];

const initialMessages: Record<string, Message[]> = {
  'c1': [
    { id: '1', text: 'Hey, is anyone receiving this? We are at the community center.', sent: true, time: '10:42 AM', status: 'delivered' },
    { id: '2', text: 'Yes, loud and clear. How is the situation there?', sent: false, time: '10:45 AM', status: 'delivered' },
  ],
  'c2': [
    { id: '3', text: 'Has everyone made it out?', sent: true, time: '09:00 AM', status: 'delivered' },
    { id: '4', text: 'We reached the shelter.', sent: false, time: '09:30 AM', status: 'delivered' },
    { id: '5', text: 'Still waiting on Dad.', sent: false, time: '09:32 AM', status: 'delivered' },
  ]
};

function App() {
  const [activeTab, setActiveTab] = useState<Tab>('chat');
  const [isConnected, setIsConnected] = useState(true);
  const [messages, setMessages] = useState<Record<string, Message[]>>(initialMessages);
  const [activeContactId, setActiveContactId] = useState<string | null>(null);
  const [inputValue, setInputValue] = useState('');

  const activeContact = mockContacts.find(c => c.id === activeContactId);
  const currentMessages = activeContactId ? (messages[activeContactId] || []) : [];

  const handleSend = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputValue.trim() || !activeContactId) return;

    const newMessage: Message = {
      id: Date.now().toString(),
      text: inputValue,
      sent: true,
      time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      status: isConnected ? 'pending' : 'pending'
    };

    setMessages({
      ...messages,
      [activeContactId]: [...currentMessages, newMessage]
    });
    setInputValue('');

    if (isConnected) {
      setTimeout(() => {
        setMessages(prev => {
          const updatedChat = prev[activeContactId].map(m => 
            m.id === newMessage.id ? { ...m, status: 'sent' as const } : m
          );
          return { ...prev, [activeContactId]: updatedChat };
        });
      }, 1000);
    }
  };

  const renderContactList = () => (
    <div className="contact-list animate-fade-in">
      <h2 style={{ fontSize: '1.2rem', padding: '16px', borderBottom: '1px solid var(--border)' }}>Conversations</h2>
      <div style={{ overflowY: 'auto', flex: 1 }}>
        {mockContacts.map(contact => (
          <div key={contact.id} className="contact-item" onClick={() => setActiveContactId(contact.id)}>
            <div className="contact-avatar">
              {contact.type === 'channel' ? <Users size={20} /> : <div className="avatar-initial">{contact.name.charAt(0)}</div>}
              {contact.online && <div className="online-indicator"></div>}
            </div>
            <div className="contact-info">
              <div className="contact-header">
                <span className="contact-name">{contact.name}</span>
                {contact.unread > 0 && <span className="unread-badge">{contact.unread}</span>}
              </div>
              <div className="contact-sub">
                <span className="node-id">{contact.nodeId}</span>
                <span className="last-msg">{contact.lastMessage}</span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );

  const renderChatThread = () => (
    <div className="chat-thread animate-slide-up">
      <div className="chat-header">
        <button className="back-btn" onClick={() => setActiveContactId(null)}>
          <ArrowLeft size={20} />
        </button>
        <div className="chat-header-info">
          <span className="chat-header-name">{activeContact?.name}</span>
          <span className="chat-header-node">{activeContact?.nodeId} • {activeContact?.type === 'channel' ? 'Broadcast Channel' : 'Direct P2P'}</span>
        </div>
      </div>

      <div className="message-list">
        <div style={{ textAlign: 'center', fontSize: '0.8rem', color: 'var(--text-secondary)', marginBottom: '16px' }}>
          E2E Encrypted • Store-and-Forward Active
        </div>
        
        {currentMessages.length === 0 && (
          <div style={{ textAlign: 'center', color: 'var(--text-secondary)', marginTop: '20px' }}>
            No messages yet. Start the conversation.
          </div>
        )}

        {currentMessages.map((msg) => (
          <div key={msg.id} className={`message ${msg.sent ? 'sent' : 'received'}`}>
            <div className="message-bubble">
              {msg.text}
            </div>
            <div className="message-meta">
              <span>{msg.time}</span>
              {msg.sent && (
                <span>
                  {msg.status === 'delivered' ? <CheckCircle2 size={12} color="var(--success)"/> : 
                   msg.status === 'sent' ? <CheckCircle2 size={12} /> : 
                   <Clock size={12} />}
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
      
      <form className="input-area" onSubmit={handleSend}>
        <input 
          type="text" 
          className="message-input" 
          placeholder={isConnected ? `Message ${activeContact?.name}...` : "Offline - Will send when hub reconnects"} 
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
        />
        <button type="submit" className="send-btn" disabled={!inputValue.trim()}>
          <Send size={18} />
        </button>
      </form>
    </div>
  );

  const renderChat = () => (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
      {activeContactId ? renderChatThread() : renderContactList()}
    </div>
  );

  const renderNetwork = () => (
    <div className="main-content animate-fade-in">
      <h2 style={{ fontSize: '1.2rem', marginBottom: '16px' }}>Mesh Network Status</h2>
      
      <div className="dashboard-grid">
        <div className="glass-panel dash-card">
          <div className="dash-card-icon"><Activity size={20} /></div>
          <div className="dash-card-value">24</div>
          <div className="dash-card-label">Nodes in Range</div>
        </div>
        <div className="glass-panel dash-card">
          <div className="dash-card-icon"><Network size={20} /></div>
          <div className="dash-card-value">3</div>
          <div className="dash-card-label">Hops (Avg)</div>
        </div>
      </div>

      <div className="glass-panel" style={{ padding: '20px' }}>
        <h3 style={{ fontSize: '1rem', marginBottom: '12px', color: 'var(--text-secondary)' }}>Local Hub Connection</h3>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingBottom: '12px', borderBottom: '1px solid var(--border)' }}>
          <span>Status</span>
          <span style={{ color: isConnected ? 'var(--success)' : 'var(--sos)' }}>{isConnected ? 'Connected' : 'Disconnected'}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: '12px' }}>
          <span>Hub ID</span>
          <span style={{ fontFamily: 'monospace', color: 'var(--text-secondary)' }}>Node-Alpha-7B</span>
        </div>
      </div>
    </div>
  );

  const renderBroadcast = () => (
    <div className="main-content animate-fade-in">
      <h2 style={{ fontSize: '1.2rem', marginBottom: '16px' }}>Situational Bulletins</h2>
      
      <div className="glass-panel" style={{ padding: '16px', marginBottom: '12px', borderLeft: '4px solid var(--accent)' }}>
        <div style={{ fontSize: '0.8rem', color: 'var(--accent)', marginBottom: '8px', fontWeight: 600 }}>SHELTER UPDATE • 2 miles away</div>
        <p style={{ fontSize: '0.95rem', lineHeight: '1.4' }}>High school gym shelter is at capacity. Redirecting to downtown community center.</p>
        <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '8px' }}>Received 12 mins ago • TTL: 4 hops remaining</div>
      </div>

      <div className="glass-panel" style={{ padding: '16px', marginBottom: '12px', borderLeft: '4px solid var(--sos)' }}>
        <div style={{ fontSize: '0.8rem', color: 'var(--sos)', marginBottom: '8px', fontWeight: 600 }}>HAZARD ALARM • 0.5 miles away</div>
        <p style={{ fontSize: '0.95rem', lineHeight: '1.4' }}>Main bridge on 5th Ave has collapsed. Do not attempt crossing.</p>
        <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '8px' }}>Received 2 mins ago • TTL: 12 hops remaining</div>
      </div>
    </div>
  );

  return (
    <div className="app-container">
      {/* Header */}
      <header className="top-nav">
        <div className="app-title">
          <Network size={22} color="var(--accent)" />
          MeshLink <span style={{ fontSize: '0.7rem', backgroundColor: 'var(--bg-tertiary)', padding: '2px 6px', borderRadius: '4px', verticalAlign: 'middle', marginLeft: '4px' }}>PWA</span>
        </div>
        <div className={`hub-status ${!isConnected ? 'disconnected' : ''}`} onClick={() => setIsConnected(!isConnected)} style={{ cursor: 'pointer' }}>
          <div className={`status-indicator ${!isConnected ? 'disconnected' : ''}`}></div>
          {isConnected ? <Wifi size={14} /> : <WifiOff size={14} />}
          {isConnected ? 'Hub Connected' : 'No Hub'}
        </div>
      </header>

      {/* SOS Button Overlay */}
      <div className="sos-container">
        <button className="sos-btn" onClick={() => alert('SOS Flood Initiated! Broadcasting to all nodes in range.')}>
          SOS
        </button>
      </div>

      {/* Main Content Area */}
      {activeTab === 'chat' && renderChat()}
      {activeTab === 'network' && renderNetwork()}
      {activeTab === 'broadcast' && renderBroadcast()}

      {/* Bottom Navigation */}
      <nav className="bottom-nav">
        <button 
          className={`nav-item ${activeTab === 'chat' ? 'active' : ''}`}
          onClick={() => setActiveTab('chat')}
        >
          <MessageSquare size={22} />
          <span>Chat</span>
        </button>
        <button 
          className={`nav-item ${activeTab === 'broadcast' ? 'active' : ''}`}
          onClick={() => setActiveTab('broadcast')}
        >
          <Radio size={22} />
          <span>Broadcast</span>
        </button>
        <button 
          className={`nav-item ${activeTab === 'network' ? 'active' : ''}`}
          onClick={() => setActiveTab('network')}
        >
          <Network size={22} />
          <span>Network</span>
        </button>
      </nav>
    </div>
  );
}

export default App;
