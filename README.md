# 🎯 ContextCraft - IntelliJ Plugin

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-blue.svg)](https://plugins.jetbrains.com/)

ContextCraft is an IntelliJ plugin that enhances AI-assisted development by intelligently copying source files and their related dependencies. Perfect for providing comprehensive context to LLMs for better code suggestions, documentation, and test generation.

*Note: Looking for the GoLand version? Check out [ContextCraft for GoLand](https://github.com/balakumardev/ContextCraft-GoLand)*

## 🚀 Features

- One-click copying of source files with related dependencies
- Smart package detection and relationship mapping
- Support for multiple JVM languages (.java, .kt, .scala, .groovy)
- Context menu integration in Editor and Project views

## ⚠️ LLM Context Size Note

The plugin can generate substantial content. Recommended LLMs by context size:

- Google Gemini (2M tokens)
- Claude-3 Opus (200K tokens)
- GPT-4 Turbo (128K tokens)
- Claude-3 Sonnet (100K tokens)
- GPT-4 (32K tokens)

## 🛠️ Installation

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
3. Paste the content into your preferred LLM

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

- [ ] Customizable package depth settings
- [ ] File type filtering options
- [ ] Size limit controls
- [ ] Format templates for different LLMs
- [ ] Direct LLM API integration
- [ ] Smart content trimming
- [ ] Multiple file selection support

## ⭐ Support

If you find this plugin useful, please:
- Give it a star on GitHub
- Share it with your network
- Report issues or suggest improvements

## 📫 Contact

Bala Kumar - [mail@balakumar.dev](mail@balakumar.dev)


---

*Empowering developers with better AI understanding of their code* 🚀