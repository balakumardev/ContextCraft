# 🎯 ContextCraft - IntelliJ Plugin

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-blue.svg)](https://plugins.jetbrains.com/)

ContextCraft is an IntelliJ plugin that enhances AI-assisted development by intelligently copying source files and their related dependencies. Perfect for providing comprehensive context to LLMs for better code suggestions, documentation, and test generation.

*Note: Looking for the GoLand version? Check out [ContextCraft for GoLand](https://github.com/balakumardev/ContextCraft-GoLand)*

## 🚀 Features

- One-click copying of source files with related dependencies
- Three relatedness levels (Strict, Medium, Broad) for precise control
- Smart package detection and relationship mapping
- Configurable depth for reference tracking
- Support for multiple JVM languages (.java, .kt, .scala, .groovy)
- Interface implementation tracking
- External dependency support with decompiled library classes
- Java standard library filtering
- Smart content pruning
- Persistent settings for your preferred configuration
- Context menu integration in Editor and Project views

## 🛠️ Installation

### Easy Installation (Recommended)

1. Download the latest release ZIP file from the [Releases page](https://github.com/balakumardev/ContextCraft/releases)
2. In IntelliJ IDEA, go to `Settings` → `Plugins`
3. Click the gear icon (⚙️) and select `Install Plugin from Disk...`
4. Choose the downloaded ZIP file
5. Restart IntelliJ IDEA when prompted

### Building from Source

1. Clone the repository:
```bash
git clone https://github.com/balakumardev/ContextCraft
```

2. Build:
```bash
./gradlew buildPlugin
```

3. Install in IntelliJ:
- Go to `Settings → Plugins`
- Click the gear icon (⚙️)
- Select `Install Plugin from Disk...`
- Choose the ZIP file from `build/distributions/`

## 📖 Usage

1. Right-click on any source file in Project view or Editor
2. Select "Copy File with Related Files"
3. Configure options in the dialog:
    - Choose relatedness level (Strict, Medium, Broad)
    - Set depth for reference tracking
    - Enable/disable interface implementations
    - Configure package filtering
    - Toggle Java standard library exclusion
4. Paste the content into your preferred LLM
5. Ask the LLM to analyze, explain, or enhance your code

## 🔧 Requirements

- IntelliJ IDEA 2023.1 or later
- Java 17 or later
- Kotlin plugin installed

## 🤝 Contributing

Contributions are welcome! Please feel free to:

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📝 License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## 🎯 Roadmap

- [ ] Format templates for different LLMs
- [ ] Direct LLM API integration
- [ ] Multiple file selection support
- [ ] Custom file type support
- [ ] Improved Kotlin support
- [ ] Context-aware code summarization

## ⭐ Support

If you find this plugin useful, please:
- Give it a star on GitHub
- Share it with your network
- Report issues or suggest improvements

## 📫 Contact

Bala Kumar - [mail@balakumar.dev](mail@balakumar.dev)