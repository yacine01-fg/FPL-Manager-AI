# FPL Manager AI (Fantasy Premier League AI Platform)

An intelligent, native Android application for Fantasy Premier League (FPL) managers built with Kotlin, Jetpack Compose, Material 3, and Clean Architecture.

Powered by advanced statistical models, an Expected Points (xPts) prediction engine, squad optimization algorithms, and an intelligent Chip Strategy Engine for the 2026/27 season.

---

## 🌟 Key Features

### 1. Smart Dashboard (لوحة التحكم الذكية)
- Live deadline countdown calculation and gameweek tracking.
- Interactive squad view with pitch and bench layout.
- Quick squad status, team value, in-the-bank balance, and free transfers remaining.
- Real-time injury and suspension alerts banner.
- Top transfer recommendations and captaincy suggestions.

### 2. Player Analysis & Prediction Engine (محرك التحليل والتوقعات الذكي)
- Multi-metric player evaluation: form, points, minutes, starts, goal involvement %, and finishing variance.
- Granular Expected Points (xPts) model considering position-specific scoring rules, starting probability, attacking potential ($xG + xA$), clean sheet odds, saves, and bonus points ($BPS$).
- Multi-gameweek horizon forecasts with uncertainty bounds (Floor ↔ Ceiling).
- Search, filter by position, club, price, and availability.

### 3. Squad Optimizer & Transfer Manager (مُحسّن التشكيلة وإدارة التبديلات)
- Official FPL squad rules enforcement (15 players, max 3 per club, budget constraints, valid formations).
- 4 Optimization strategies:
  - **Max Expected Points (Max xPts)**
  - **Low-Risk / Template**
  - **Balanced Strategy**
  - **Differential Strategy**
- Transfer planning with free transfers rollover (1-5 FTs) and hit penalty (-4) calculations.
- Keep vs Sell comparative analysis.

### 4. Intelligent Chip Strategy Engine (محرك إدارة الشرائح لموسم 2026/27)
- **Two sets of four chips per season (8 chips total)**: Set 1 (GW 1-19) and Set 2 (GW 20-38).
- **Official Rules Engine**:
  - Enforces first-half expiration after GW19 deadline (no carry-over between halves).
  - Second set activation from GW20.
  - Max 1 chip per gameweek with automatic arbitration.
  - Prohibits Free Hit in consecutive gameweeks.
  - Prohibits Free Hit and Wildcard in Gameweek 1.
  - Preserves banked free transfers on Wildcard and Free Hit activation.
- **Analyzers**:
  - **Triple Captain Analyzer**: Calculates incremental expected value ($1 \times xPts$), evaluates single vs double gameweeks, starting probability, and EV(Now) vs EV(Future).
  - **Bench Boost Analyzer**: Evaluates all 4 bench players, availability, starting certainty, and strictly rejects weak or doubtful benches.
  - **Free Hit Analyzer**: Detects blank gameweeks, optimizes temporary 15-player squad, and safely restores original squad.
  - **Wildcard Analyzer**: Squad health audit, 5-GW fixture swing evaluation, hit cost savings, and Set 1 deadline expiration warnings.
- **Historical Learning & Calibration**:
  - Automatically captures pre-deadline recommendation snapshots.
  - Records post-gameweek actual points and calculates prediction error ($MAE$, bias).
  - Dynamically updates calibration factor without modifying historical records.

---

## 🛠️ Architecture & Tech Stack

- **Platform**: Android Native (Min SDK 26, Target SDK 35)
- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose + Material 3 (Full RTL Arabic Support)
- **Architecture**: MVVM + Clean Architecture (Data, Domain, Presentation)
- **Networking**: Retrofit 2 + Gson
- **Concurrency**: Kotlin Coroutines + StateFlow
- **Caching**: Multi-tier local cache (Memory + Persistent File-based Cache with TTL)
- **Testing**: JUnit 4 (Unit tests covering models, rules, analyzers, cache, and validation)

---

## 📁 Project Structure

```text
com.fplmanagerai/
├── data/
│   ├── cache/             # Local Data Cache (TTL & freshness)
│   ├── chips/             # Season Config & Historical Learning Repository
│   ├── model/             # Player, Team, Fixture, Gameweek models
│   ├── network/           # Retrofit API & DataResult wrappers
│   ├── parser/            # Response validation & safe deserialization
│   └── repository/        # Player & Fixture repositories
├── domain/
│   ├── analysis/          # Expected points, rankings, fixture difficulty
│   ├── chips/             # Rules Engine, Analyzers (3XC, BB, FH, WC), Calibration
│   └── team/              # Squad Optimizer, Formations, Captain Selector, Transfers
└── presentation/
    ├── analysis/          # Player analysis screen & ViewModel
    ├── chips/             # Chip Manager dashboard screen & ViewModel
    ├── components/        # Reusable Compose cards & UI widgets
    └── dashboard/         # Smart dashboard screen & ViewModel
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 35

### Running the App
1. Clone the repository:
   ```bash
   git clone https://github.com/yacine01-fg/FPL-Manager-AI.git
   ```
2. Open the project in Android Studio.
3. Allow Gradle to sync dependencies.
4. Run on an Android emulator or physical device.

### Running Unit Tests
Execute the comprehensive unit test suite via Gradle:
```bash
./gradlew test
```

### Building the Debug APK
```bash
./gradlew assembleDebug
```

---

## 📄 License
This project is for educational and personal use under the MIT License.
Data is retrieved from the public Fantasy Premier League API. FPL is a registered trademark of the Premier League.
