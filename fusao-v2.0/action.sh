#
# Copyright (C) 2024-2026 Rem01Gaming
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# FUSAO-M54: botão de ação abre o app FUSAO (única interface do módulo;
# a WebUI foi extinta — CLI é o mecanismo, app é a interface).
if [ -n "$MMRL" ]; then
	echo "- Abra o app FUSAO (action não roda no MMRL)."
	exit 0
fi

pm path com.orkgabb.fusao >/dev/null 2>&1 && {
	echo "- Abrindo app FUSAO..."
	am start -n "com.orkgabb.fusao/.MainActivity" >/dev/null 2>&1
	exit 0
}

echo "! App FUSAO não instalado (única interface do módulo)"
exit 1
