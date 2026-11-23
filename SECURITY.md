# 安全说明

- 仓库里不放连接参数和安装包签名凭据，每次提交都有自动检查（[`tools/check_public_tree.py`](tools/check_public_tree.py)）。
- 从源码构建需要自己准备连接参数文件，见 README「构建」。
- 往 Issue 里贴日志前先看一遍，别贴配对记录之类的内容。
- 发现安全问题，请用 GitHub 的私密漏洞报告（仓库页「Security」→「Report a vulnerability」），不要公开发 Issue。
