✈️ AeroSync — Aviation Delay Operations Agent

An agentic AI system that autonomously manages cascading flight disruptions by coordinating specialized sub-agents for gate reassignment, crew reallocation, aircraft swaps, and passenger rebooking — in real time on a simulated airport environment.

📌 Table of Contents

Problem Statement
What This Project Does
System Architecture
Agent Design
Tech Stack
Project Structure
Dummy Data Model
Disruption Scenarios
API Reference
Setup & Running Locally
Sample Output
Roadmap
Why This Project


🚨 Problem Statement
When a single flight is delayed, it triggers a cascade of interconnected failures:

Crew assigned to the delayed flight may breach duty hour limits
Gates scheduled for the next aircraft become blocked
Connecting passengers miss onward flights
Aircraft may be mispositioned for the next leg

Airlines today handle this with manual coordination between operations teams — a slow, error-prone process that costs the global aviation industry an estimated $60 billion annually in delay-related losses.
AeroSync simulates how an agentic AI system could autonomously detect, plan, and resolve these cascading disruptions within seconds.

🎯 What This Project Does

Simulates a realistic airport environment — 20 flights, 15 crew, 8 gates, 10 aircraft, 100 passengers — all as structured dummy data
Injects disruptions — weather events, mechanical failures, crew timeouts — via a simulator engine
Activates the orchestrator agent — which reads the disruption, plans a resolution strategy, and delegates to specialized sub-agents
Sub-agents resolve constraints independently and report back
Generates a resolution report — new assignments, rebooked passengers, estimated cost impact


🏗️ System Architecture
┌─────────────────────────────────────────────────────────┐
│                    Disruption Simulator                  │
│         (injects delay events into airport state)        │
└────────────────────────┬────────────────────────────────┘
                         │ REST trigger
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Boot Backend (Java 21)               │
│                                                          │
│   ┌──────────────┐        ┌──────────────────────────┐  │
│   │ Airport State│◄──────►│   Orchestrator Service   │  │
│   │  (In-memory) │        │   (calls Python agent)   │  │
│   └──────────────┘        └────────────┬─────────────┘  │
│                                        │ HTTP / subprocess│
└────────────────────────────────────────┼────────────────┘
                                         │
                         ┌───────────────▼──────────────┐
                         │   Python Agent Layer          │
                         │                               │
                         │  ┌─────────────────────────┐ │
                         │  │  Orchestrator Agent      │ │
                         │  │  (Claude API)            │ │
                         │  └────────────┬────────────┘ │
                         │               │               │
                         │   ┌───────────┼───────────┐  │
                         │   ▼           ▼           ▼  │
                         │ Gate       Crew      Passenger│
                         │ Agent      Agent     Agent    │
                         │   │           │           │   │
                         │   └───────────┼───────────┘  │
                         │               ▼               │
                         │       Aircraft Agent          │
                         └───────────────────────────────┘

🤖 Agent Design
Orchestrator Agent

Receives the full airport state snapshot + disruption event
Uses Claude API (function calling) to decide which sub-agents to invoke and in what order
Handles conflict resolution when sub-agent outputs are incompatible (e.g. spare crew not certified for available aircraft)
Assembles the final resolution report

Gate Agent
Responsibility: Find an available compatible gate for the delayed/rerouted flight
Constraints checked:

Gate availability window
Terminal proximity to aircraft current position
Gate size compatibility with aircraft type

Crew Agent
Responsibility: Find a valid replacement crew if original crew breaches duty limits
Constraints checked:

Crew duty hours remaining (max_duty_hours - duty_hours_used)
Crew current location (must be at same airport)
Crew certification for aircraft type
Minimum rest period since last flight

Passenger Agent
Responsibility: Rebook passengers who miss connecting flights due to the delay
Constraints checked:

Connection time window (passenger misses connection if delay > buffer)
Next available flight to same destination
Seat availability on alternate flight
Passenger tier priority (business class / VIP rebooked first)

Aircraft Agent
Responsibility: Swap aircraft if the original is grounded for maintenance
Constraints checked:

Replacement aircraft current location
Aircraft type compatibility with assigned crew certifications
Maintenance flag status


🛠️ Tech Stack
LayerTechnologyPurposeBackend APIJava 21 + Spring Boot 3.xREST endpoints, airport state management, agent orchestration triggerAI Agent LayerPython 3.11Claude API integration, multi-agent logic, tool definitionsLLMAnthropic Claude (claude-sonnet-4-6)Orchestrator reasoning, constraint-aware planningDataJSON flat filesSimulated airport world (flights, crew, gates, aircraft, passengers)CommunicationREST (Spring → Python)Backend calls Python agent service via HTTPBuild ToolMavenJava dependency managementPackage Managerpip / venvPython dependency management

📁 Project Structure
aerosync/
│
├── backend/                          # Java Spring Boot
│   ├── src/main/java/com/aerosync/
│   │   ├── controller/
│   │   │   ├── DisruptionController.java     # POST /api/disruption/trigger
│   │   │   └── StateController.java          # GET /api/state
│   │   ├── service/
│   │   │   ├── AirportStateService.java      # Manages in-memory airport state
│   │   │   ├── OrchestratorService.java      # Calls Python agent layer
│   │   │   └── SimulatorService.java         # Injects disruption events
│   │   ├── model/
│   │   │   ├── Flight.java
│   │   │   ├── Crew.java
│   │   │   ├── Gate.java
│   │   │   ├── Aircraft.java
│   │   │   └── Passenger.java
│   │   └── AeroSyncApplication.java
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── data/                             # Dummy JSON data files
│   │       ├── flights.json
│   │       ├── crew.json
│   │       ├── gates.json
│   │       ├── aircraft.json
│   │       └── passengers.json
│   └── pom.xml
│
├── agents/                           # Python AI layer
│   ├── orchestrator.py               # Main Claude-powered orchestrator
│   ├── tools/
│   │   ├── gate_agent.py             # Gate reassignment logic + tool def
│   │   ├── crew_agent.py             # Crew reallocation logic + tool def
│   │   ├── passenger_agent.py        # Passenger rebooking logic + tool def
│   │   └── aircraft_agent.py         # Aircraft swap logic + tool def
│   ├── models/
│   │   └── airport_state.py          # Pydantic models for state parsing
│   ├── api_server.py                 # Flask server exposing agents to Spring
│   └── requirements.txt
│
├── simulator/                        # Disruption event generator
│   └── disruption_generator.py       # Generates random or scripted events
│
├── docs/
│   └── sample_resolution_report.json # Example agent output
│
└── README.md

📦 Dummy Data Model
Flight
json{
  "id": "AI-202",
  "origin": "BOM",
  "destination": "DEL",
  "scheduled_departure": "14:30",
  "actual_departure": null,
  "status": "delayed",
  "delay_minutes": 180,
  "aircraft_id": "VT-ANB",
  "gate_id": "B4",
  "passenger_count": 142
}
Crew Member
json{
  "id": "C-07",
  "name": "Capt. Arjun Mehta",
  "role": "captain",
  "assigned_flight": "AI-202",
  "current_location": "BOM",
  "duty_hours_used": 9.5,
  "max_duty_hours": 12,
  "aircraft_certifications": ["A320", "A321"],
  "status": "active"
}
Gate
json{
  "id": "B4",
  "terminal": "T2",
  "compatible_aircraft": ["A320", "A321", "B737"],
  "occupied_by": "AI-202",
  "available_from": "17:30"
}
Passenger
json{
  "id": "P-441",
  "name": "Sneha Kapoor",
  "booked_flight": "AI-202",
  "connection_flight": "AI-305",
  "connection_departure": "17:00",
  "connection_buffer_minutes": 45,
  "tier": "business"
}

💥 Disruption Scenarios
The simulator can inject the following disruption types:
ScenarioTriggerAgents ActivatedWeather delayFog/storm at origin airportGate + Crew + PassengerCrew duty breachCaptain exceeds 12hr duty limitCrew + Aircraft (if cert mismatch)Mechanical groundingAircraft flagged for maintenanceAircraft + Gate + PassengerGate conflictTwo flights assigned same gateGate onlyFull cascadeWeather + crew timeout combinedAll 4 agents

🔌 API Reference
Trigger a Disruption
POST /api/disruption/trigger
Content-Type: application/json

{
  "flight_id": "AI-202",
  "disruption_type": "WEATHER_DELAY",
  "delay_minutes": 180,
  "reason": "Dense fog at BOM, visibility below minimums"
}
Get Current Airport State
GET /api/state
Get Resolution Report
GET /api/disruption/report/{disruption_id}
Sample Response:
json{
  "disruption_id": "D-001",
  "flight_id": "AI-202",
  "resolution_status": "RESOLVED",
  "actions_taken": {
    "gate_reassigned": { "from": "B4", "to": "C2", "reason": "B4 conflict at 17:30" },
    "crew_swapped": { "original": "C-07", "replacement": "C-12", "reason": "Duty hour breach" },
    "passengers_rebooked": { "count": 38, "alternate_flight": "AI-208", "departure": "18:15" },
    "aircraft_swapped": null
  },
  "estimated_cost_impact_inr": 245000,
  "resolution_time_ms": 1840
}

🚀 Setup & Running Locally
Prerequisites

Java 21 (Adoptium Temurin recommended — adoptium.net)
Python 3.11+
Maven 3.8+
Anthropic API key (console.anthropic.com)

1. Clone the repository
bashgit clone https://github.com/yourusername/aerosync.git
cd aerosync
2. Set up Python agent layer
bashcd agents
python -m venv venv
source venv/bin/activate        # Windows: venv\Scripts\activate
pip install -r requirements.txt

# Set your API key
export ANTHROPIC_API_KEY=your_key_here

# Start the agent API server
python api_server.py            # runs on port 5001
3. Start the Spring Boot backend
bashcd ../backend
mvn spring-boot:run             # runs on port 8080
4. Trigger a disruption
bashcurl -X POST http://localhost:8080/api/disruption/trigger \
  -H "Content-Type: application/json" \
  -d '{"flight_id":"AI-202","disruption_type":"WEATHER_DELAY","delay_minutes":180}'

📄 Sample Output
[AEROSYNC] Disruption detected: AI-202 delayed 180 mins — weather at BOM
[ORCHESTRATOR] Planning resolution for: WEATHER_DELAY
[ORCHESTRATOR] Activating: GateAgent, CrewAgent, PassengerAgent

[GATE AGENT] B4 blocked until 17:30 → Reassigning to C2 (available 14:00) ✓
[CREW AGENT] C-07 at 9.5/12 duty hrs → Will breach at 17:30 → Activating C-12 ✓
[PASSENGER AGENT] 38 passengers miss AI-305 (17:00) → Rebooked on AI-208 (18:15) ✓

[ORCHESTRATOR] All constraints resolved. Generating report...
[REPORT] Resolution complete in 1840ms | Estimated cost impact: ₹2,45,000

🗺️ Roadmap

 Project architecture design
 Dummy data model definition
 Spring Boot backend + state management
 Python agent layer + Claude API integration
 Gate agent implementation
 Crew agent implementation
 Passenger agent implementation
 Aircraft agent implementation
 Orchestrator conflict resolution logic
 Disruption simulator
 Resolution report generator
 Streamlit dashboard (optional UI)
 Unit tests for constraint logic


💡 Why This Project
Real airline operations centers employ entire teams to manually coordinate disruption responses — a process that takes 20-40 minutes and still results in suboptimal outcomes. This project demonstrates how multi-agent agentic AI can:

Solve interconnected constraint satisfaction problems autonomously
Coordinate multiple specialized agents with conflicting outputs
Make explainable, auditable decisions (every action is logged with reasoning)

The architecture mirrors what production agentic systems look like at scale — an orchestrator delegating to specialized tools — making it directly relevant to modern AI engineering roles.

📜 License
MIT License — free to use, fork, and build on.


Built as a portfolio project demonstrating agentic AI system design.
Stack: Java 21 + Spring Boot · Python 3.11 · Anthropic Claude API
Share(function(){function c(){var b=a.contentDocument||a.contentWindow.document;if(b){var d=b.createElement('script');d.nonce='Lfczs8cAYRVbFG+Cxk1wLw==';d.innerHTML="window.__CF$cv$params={r:'9e46f14ae90c3b2d',t:'MTc3NDg3MTkwNy4wMDAwMDA='};var a=document.createElement('script');a.nonce='Lfczs8cAYRVbFG+Cxk1wLw==';a.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js';document.getElementsByTagName('head')[0].appendChild(a);";b.getElementsByTagName('head')[0].appendChild(d)}}if(document.body){var a=document.createElement('iframe');a.height=1;a.width=1;a.style.position='absolute';a.style.top=0;a.style.left=0;a.style.border='none';a.style.visibility='hidden';document.body.appendChild(a);if('loading'!==document.readyState)c();else if(window.addEventListener)document.addEventListener('DOMContentLoaded',c);else{var e=document.onreadystatechange||function(){};document.onreadystatechange=function(b){e(b);'loading'!==document.readyState&&(document.onreadystatechange=e,c())}}}})();
